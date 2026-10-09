package com.tillrecorder.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
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
    private var unlocked = false

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
        unlocked = savedInstanceState?.getBoolean(STATE_UNLOCKED) == true
        binding.registerName.setText(store.deviceName)
        binding.remindRestart.isChecked = store.remindAfterRestart
        binding.recordButton.setOnClickListener {
            if (RecorderEvents.current.recording) {
                if (!store.hasPin || !unlocked) {
                    showStatus(getString(R.string.pin_required_stop))
                    render()
                    return@setOnClickListener
                }
                RecordingService.stop(this)
            } else {
                startRecordingFlow()
            }
        }
        binding.connectButton.setOnClickListener { connect() }
        binding.unlockButton.setOnClickListener { unlock() }
        binding.pinEntry.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlock()
                true
            } else {
                false
            }
        }
        binding.savePinButton.setOnClickListener { savePin() }
        binding.preventUninstall.setOnClickListener { preventUninstall() }
        binding.findCameras.setOnClickListener { findCameras() }
        binding.saveCamera.setOnClickListener { saveCamera(clear = false) }
        binding.clearCamera.setOnClickListener { saveCamera(clear = true) }
        binding.confirmPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                savePin()
                true
            } else {
                false
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    override fun onRestart() {
        super.onRestart()
        unlocked = false
        binding.pinEntry.text = null
        binding.pinError.visibility = View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onStart() {
        super.onStart()
        render()
        binding.root.post(ticker)
        LauncherIcon.apply(this, store)
        if (store.watchEnabled && store.isConfigured && TillAccessibilityService.enabled(this)) {
            RecordingService.startBuiltIn(this)
        }
        if (RecordingFiles.pendingCount(this) > 0) UploadWorker.enqueue(this)
    }

    override fun onStop() {
        binding.root.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onPause() {
        if (store.isConfigured && !controlsLocked()) {
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
        if (controlsLocked()) return
        if (!store.isConfigured) {
            showStatus("Enter the pair code from the watch page first.")
            return
        }
        if (!store.hasPin) {
            showStatus(getString(R.string.pin_required_stop))
            render()
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
        askToStayAwake()
    }

    private fun connect() {
        if (controlsLocked()) return
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
                showStatus("Connected. Set a PIN, then turn on Till Recorder in Accessibility once.")
                render()
            }
        }
    }

    private fun savePin() {
        val pin = binding.newPin.text?.toString().orEmpty()
        val confirm = binding.confirmPin.text?.toString().orEmpty()
        when {
            !PinLock.acceptable(pin) -> showPinSetupError(getString(R.string.pin_length))
            pin != confirm -> showPinSetupError(getString(R.string.pin_mismatch))
            !store.setPin(pin) -> showPinSetupError(getString(R.string.pin_length))
            else -> {
                unlocked = true
                binding.newPin.text = null
                binding.confirmPin.text = null
                binding.pinSetupError.visibility = View.GONE
                showStatus(getString(R.string.pin_saved))
                askToStayAwake()
                render()
            }
        }
    }

    private fun uninstallProtectionOn(): Boolean {
        val admin = getSystemService(DevicePolicyManager::class.java) ?: return false
        return admin.isAdminActive(ComponentName(this, UninstallGuard::class.java))
    }

    private fun preventUninstall() {
        if (!store.isConfigured || controlsLocked() || uninstallProtectionOn()) return
        val explanation = getString(R.string.prevent_uninstall_explain)
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, UninstallGuard::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        )
    }

    private fun unlock() {
        val pin = binding.pinEntry.text?.toString().orEmpty()
        if (store.checkPin(pin)) {
            unlocked = true
            binding.pinEntry.text = null
            binding.pinError.visibility = View.GONE
            render()
        } else {
            binding.pinError.setText(R.string.pin_wrong)
            binding.pinError.visibility = View.VISIBLE
        }
    }

    private fun controlsLocked(): Boolean {
        val paired = store.isConfigured && !repairing
        return paired && (!store.hasPin || !unlocked)
    }

    private fun askToStayAwake() {
        if (Build.VERSION.SDK_INT < 23) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
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
        val needsPin = paired && !store.hasPin
        val locked = paired && store.hasPin && !unlocked
        binding.recordButton.setText(if (recording) R.string.stop else R.string.start)
        binding.registerName.isEnabled = !recording
        binding.remindRestart.isEnabled = !recording
        binding.pairLayout.visibility = if (paired) View.GONE else View.VISIBLE
        binding.connectButton.visibility = if (paired) View.GONE else View.VISIBLE
        binding.lockPanel.visibility = if (locked) View.VISIBLE else View.GONE
        binding.pinSetupPanel.visibility = if (needsPin) View.VISIBLE else View.GONE
        binding.controlsPanel.visibility = if (locked || needsPin) View.GONE else View.VISIBLE
        val adminOn = uninstallProtectionOn()
        binding.preventUninstall.visibility = if (paired) View.VISIBLE else View.GONE
        binding.preventUninstallNote.visibility = if (paired) View.VISIBLE else View.GONE
        binding.preventUninstall.setText(if (adminOn) R.string.prevent_uninstall_on else R.string.prevent_uninstall)
        binding.preventUninstall.isEnabled = !adminOn
        if (locked) {
            binding.lockStatus.setText(if (recording) R.string.watching_locked else R.string.stopped_locked)
        }
        val error = store.uploadError
        if (recording) note = null
        binding.statusText.text = when {
            error.isNotBlank() -> error
            recording -> status.detail
            note != null -> note
            status.detail.isNotBlank() -> status.detail
            else -> getString(R.string.status_idle)
        }
        store.migrateOverheadCredentials()
        if (!binding.cameraUrl.hasFocus() && binding.cameraUrl.text?.toString().orEmpty() != store.overheadUrl) {
            binding.cameraUrl.setText(store.overheadUrl)
        }
        if (!binding.cameraUser.hasFocus() && binding.cameraUser.text?.toString().orEmpty() != store.overheadUser) {
            binding.cameraUser.setText(store.overheadUser)
        }
        if (!binding.cameraPassword.hasFocus() && binding.cameraPassword.text?.toString().orEmpty() != store.overheadPassword) {
            binding.cameraPassword.setText(store.overheadPassword)
        }
        binding.pendingText.text = when (pending) {
            0 -> getString(R.string.pending_clear)
            1 -> "1 clip is still on this tablet and will send when Cloudflare is reachable."
            else -> "$pending clips are still on this tablet and will send when Cloudflare is reachable."
        }
    }

    private fun findCameras() {
        if (controlsLocked()) return
        binding.findCameras.isEnabled = false
        binding.cameraStatus.setText(R.string.camera_searching)
        binding.cameraChoices.removeAllViews()
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { OnvifDiscovery.probe(this@MainActivity) }
            binding.findCameras.isEnabled = true
            if (found.isEmpty()) {
                binding.cameraStatus.setText(R.string.camera_none)
                return@launch
            }
            binding.cameraStatus.setText(R.string.camera_found)
            for (camera in found) {
                val button = com.google.android.material.button.MaterialButton(
                    this@MainActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                )
                button.text = camera.host
                button.setOnClickListener {
                    binding.cameraUrl.setText(camera.service)
                    if (binding.cameraUser.text.isNullOrBlank()) binding.cameraStatus.setText(R.string.camera_need_login)
                }
                binding.cameraChoices.addView(button)
            }
        }
    }

    private fun saveCamera(clear: Boolean) {
        if (controlsLocked()) return
        if (clear) {
            store.overheadUrl = ""
            store.overheadUser = ""
            store.overheadPassword = ""
            binding.cameraUrl.setText("")
            binding.cameraUser.setText("")
            binding.cameraPassword.setText("")
            syncCamera(cleared = true)
            return
        }
        val address = binding.cameraUrl.text?.toString().orEmpty().trim()
        val user = binding.cameraUser.text?.toString().orEmpty().trim()
        val password = binding.cameraPassword.text?.toString().orEmpty()
        val target = CameraAddress.resolve(address, user, password)
        if (target.kind == CameraAddress.Kind.INVALID) {
            binding.cameraStatus.text = target.error
            return
        }
        binding.saveCamera.isEnabled = false
        binding.cameraStatus.setText(R.string.camera_checking)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { CameraSetup.check(target) }
            val error = outcome.error
            if (error != null) {
                binding.saveCamera.isEnabled = true
                binding.cameraStatus.text = error
                return@launch
            }
            store.overheadUrl = outcome.url
            store.overheadUser = target.user
            store.overheadPassword = target.password
            binding.cameraUrl.setText(outcome.url)
            binding.cameraUser.setText(target.user)
            if (!binding.cameraPassword.hasFocus()) binding.cameraPassword.setText(target.password)
            syncCamera(cleared = false)
        }
    }

    private fun syncCamera(cleared: Boolean) {
        binding.saveCamera.isEnabled = false
        lifecycleScope.launch {
            val label = store.overheadLabel()
            val synced = withContext(Dispatchers.IO) {
                ShopClient.setOverhead(store.current(), label.isNotEmpty(), label)
            }
            RecordingService.refreshOverhead()
            binding.saveCamera.isEnabled = true
            binding.cameraStatus.setText(
                when {
                    !synced -> R.string.camera_saved_local
                    cleared -> R.string.camera_cleared
                    else -> R.string.camera_saved
                }
            )
        }
    }

    private fun showStatus(message: String) {
        note = message
        binding.statusText.text = message
    }

    private fun showPinSetupError(message: String) {
        binding.pinSetupError.text = message
        binding.pinSetupError.visibility = View.VISIBLE
    }

    companion object {
        private const val STATE_UNLOCKED = "unlocked"
    }
}
