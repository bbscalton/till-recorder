package com.tillrecorder.agent

import android.content.Context
import android.os.Environment
import java.io.File

object RecordingFiles {
    fun pendingDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "pending").apply { mkdirs() }
    }

    fun pendingCount(context: Context): Int {
        val dir = pendingDir(context)
        return dir.listFiles { file -> file.isFile && file.extension == "json" }?.size ?: 0
    }

    fun trim(dir: File, maxBytes: Long) {
        val clips = dir.listFiles { file ->
            file.isFile && file.extension == "mp4" && !file.name.startsWith("partial-")
        }?.sortedBy { it.lastModified() } ?: return
        var total = clips.sumOf { it.length() }
        for (file in clips) {
            if (total <= maxBytes) return
            total -= file.length()
            File(dir, file.nameWithoutExtension + ".json").delete()
            file.delete()
        }
    }
}
