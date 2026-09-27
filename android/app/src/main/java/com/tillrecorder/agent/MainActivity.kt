package com.tillrecorder.agent

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tillrecorder.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val audioOk = granted[Manifest.permission.RECORD_AUDIO] != false &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        val notificationsOk = Build.VERSION.SDK_INT < 33 ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (audioOk && notificationsOk) {
            launchCaptureConsent()
        } else {
            showStatus("Allow the microphone and notifications, then start recording again.")
        }
    }

    private val captureRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            RecordingService.start(this, result.resultCode, data)
        } else {
            showStatus("Screen capture was not allowed. Choose Entire screen to record the register.")
        }
    }

    private var note: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            binding.root.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        binding.registerName.setText(store.deviceName)
        binding.serverUrl.setText(store.baseUrl)
        binding.token.setText(store.token)
        binding.remindRestart.isChecked = store.remindAfterRestart
        binding.recordButton.setOnClickListener {
            if (RecorderEvents.current.recording) {
                RecordingService.stop(this)
            } else {
                startRecordingFlow()
            }
        }
        binding.testButton.setOnClickListener { testConnection() }
        render()
    }

    override fun onStart() {
        super.onStart()
        binding.root.post(ticker)
        if (RecordingFiles.pendingCount(this) > 0) UploadWorker.enqueue(this)
    }

    override fun onStop() {
        binding.root.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    private fun startRecordingFlow() {
        val settings = validatedSettings() ?: return
        store.save(settings.deviceName, settings.baseUrl, settings.token, binding.remindRestart.isChecked)
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            launchCaptureConsent()
        } else {
            permissionRequest.launch(missing)
        }
    }

    private fun testConnection() {
        val settings = validatedSettings() ?: return
        binding.testButton.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ShopClient.checkToken(settings) }
            binding.testButton.isEnabled = true
            showStatus(
                if (ok) "Connected to Cloudflare."
                else "Could not reach Cloudflare, or the token does not match."
            )
        }
    }

    private fun validatedSettings(): ShopSettings? {
        saveFields()
        val settings = store.current()
        val normalized = SettingsStore.normalizeBaseUrl(settings.baseUrl)
        if (settings.deviceName.isBlank()) {
            showStatus("Enter a name for this register.")
            return null
        }
        if (normalized == null) {
            showStatus("Enter the Cloudflare address, like https://till-recorder.workers.dev")
            return null
        }
        if (settings.token.isBlank()) {
            showStatus("Enter the token from the recorder setup.")
            return null
        }
        return settings.copy(baseUrl = normalized)
    }

    private fun saveFields() {
        store.save(
            name = binding.registerName.text?.toString().orEmpty(),
            baseUrl = binding.serverUrl.text?.toString().orEmpty(),
            token = binding.token.text?.toString().orEmpty(),
            remind = binding.remindRestart.isChecked,
        )
    }

    private fun launchCaptureConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            captureIntentForWholeScreen(manager)
        } else {
            manager.createScreenCaptureIntent()
        }
        captureRequest.launch(intent)
    }

    private fun missingPermissions(): Array<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        return needed.filterNot { hasPermission(it) }.toTypedArray()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun render() {
        val status = RecorderEvents.current
        val pending = RecordingFiles.pendingCount(this)
        val recording = status.recording
        binding.recordButton.setText(if (recording) R.string.stop else R.string.start)
        binding.registerName.isEnabled = !recording
        binding.serverUrl.isEnabled = !recording
        binding.token.isEnabled = !recording
        binding.remindRestart.isEnabled = !recording
        val error = store.uploadError
        if (recording) note = null
        binding.statusText.text = when {
            recording -> status.detail
            note != null -> note
            error.isNotBlank() -> error
            status.detail.isNotBlank() -> status.detail
            else -> getString(R.string.status_idle)
        }
        binding.pendingText.text = when (pending) {
            0 -> getString(R.string.pending_clear)
            1 -> "1 clip is still on this tablet and will send when Cloudflare is reachable."
            else -> "$pending clips are still on this tablet and will send when Cloudflare is reachable."
        }
    }

    private fun showStatus(message: String) {
        note = message
        binding.statusText.text = message
    }
}
