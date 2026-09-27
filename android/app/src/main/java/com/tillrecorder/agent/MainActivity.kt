package com.tillrecorder.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
            startWatching()
        } else {
            showStatus("Allow the microphone and notifications, then start watching again.")
        }
    }

    private var note: String? = null
    private var repairing = false

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
        binding.remindRestart.isChecked = store.remindAfterRestart
        binding.recordButton.setOnClickListener {
            if (RecorderEvents.current.recording) {
                RecordingService.stop(this)
            } else {
                startRecordingFlow()
            }
        }
        binding.connectButton.setOnClickListener { connect() }
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
        if (store.isConfigured) {
            store.save(
                binding.registerName.text?.toString().orEmpty().ifBlank { store.deviceName },
                SettingsStore.DEFAULT_URL,
                store.token,
                binding.remindRestart.isChecked,
            )
        }
        super.onPause()
    }

    private fun startRecordingFlow() {
        if (!store.isConfigured) {
            showStatus("Enter the pair code from the watch page first.")
            return
        }
        store.save(
            binding.registerName.text?.toString().orEmpty().ifBlank { store.deviceName },
            SettingsStore.DEFAULT_URL,
            store.token,
            binding.remindRestart.isChecked,
        )
        if (Build.VERSION.SDK_INT < 30) {
            showStatus("This tablet needs Android 11 or newer.")
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startWatching()
        } else {
            permissionRequest.launch(missing)
        }
    }

    private fun startWatching() {
        if (!TillAccessibilityService.enabled(this)) {
            showStatus("Turn on Till Recorder in Accessibility. You only do this once, then come back and tap Start.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        RecordingService.startBuiltIn(this)
    }

    private fun connect() {
        val name = binding.registerName.text?.toString()?.trim().orEmpty()
        val code = binding.pairCode.text?.toString().orEmpty()
        if (name.isBlank()) {
            showStatus("Enter a name for this register.")
            return
        }
        if (code.count { it.isLetterOrDigit() } < 6) {
            showStatus("Enter the 6-character pair code from the watch page.")
            return
        }
        binding.connectButton.isEnabled = false
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) { ShopClient.claimPair(store.deviceId, name, code) }
            binding.connectButton.isEnabled = true
            if (token.isNullOrBlank()) {
                showStatus("That pair code did not work. Create a new one on the watch page.")
            } else {
                store.save(name, SettingsStore.DEFAULT_URL, token, binding.remindRestart.isChecked)
                repairing = false
                binding.pairCode.text = null
                showStatus("Connected. Turn on Till Recorder in Accessibility once, then start watching.")
                render()
            }
        }
    }

    private fun missingPermissions(): Array<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
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
        val paired = store.isConfigured && !repairing
        binding.recordButton.setText(if (recording) R.string.stop else R.string.start)
        binding.registerName.isEnabled = !recording
        binding.remindRestart.isEnabled = !recording
        binding.pairLayout.visibility = if (paired) View.GONE else View.VISIBLE
        binding.connectButton.visibility = if (paired) View.GONE else View.VISIBLE
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
