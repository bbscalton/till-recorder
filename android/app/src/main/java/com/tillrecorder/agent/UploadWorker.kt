package com.tillrecorder.agent

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val settings = store.current()
        if (SettingsStore.normalizeBaseUrl(settings.baseUrl) == null || settings.token.isBlank()) {
            store.uploadError = "Add the Cloudflare address and token, then arm the register again."
            return Result.failure()
        }
        val deadline = System.currentTimeMillis() + 8 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val job = nextJob() ?: run {
                store.uploadError = ""
                return Result.success()
            }
            val (json, mp4) = job
            val meta = readMeta(json, mp4) ?: continue
            when (val result = ShopClient.upload(settings, meta, mp4)) {
                UploadResult.Ok -> {
                    mp4.delete()
                    json.delete()
                    store.uploadError = ""
                }
                UploadResult.Unauthorized -> {
                    store.uploadError = "The token was rejected. Check it matches the Cloudflare recorder."
                    return Result.failure()
                }
                is UploadResult.Failed -> {
                    store.uploadError = result.detail
                    Log.w(TAG, result.detail)
                    return Result.retry()
                }
            }
        }
        return if (RecordingFiles.pendingCount(applicationContext) == 0) Result.success() else Result.retry()
    }

    private fun nextJob(): Pair<File, File>? {
        val dir = RecordingFiles.pendingDir(applicationContext)
        val jsons = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            ?: return null
        for (json in jsons) {
            val mp4 = File(dir, json.nameWithoutExtension + ".mp4")
            if (mp4.exists()) return json to mp4
            json.delete()
        }
        return null
    }

    private fun readMeta(json: File, mp4: File): SegmentMeta? {
        return try {
            val obj = JSONObject(json.readText())
            val kind = obj.optString("kind", "screen")
            SegmentMeta(
                obj.getLong("startedAtMs"),
                obj.getLong("endedAtMs"),
                if (kind == "camera") "camera" else "screen",
            )
        } catch (error: Exception) {
            Log.w(TAG, "Dropping unreadable clip ${json.name}", error)
            json.delete()
            mp4.delete()
            null
        }
    }

    companion object {
        private const val TAG = "TillRecorder"
        private const val WORK_NAME = "till-upload"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
