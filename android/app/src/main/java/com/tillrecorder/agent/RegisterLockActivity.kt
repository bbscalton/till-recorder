package com.tillrecorder.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tillrecorder.agent.databinding.ActivityLockBinding
import kotlin.concurrent.thread

class RegisterLockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockBinding
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            }
        )
        binding.lockUnlock.setOnClickListener { tryUnlock() }
        binding.lockPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }
        if (!RegisterLock.isEngaged()) finish()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        if (!RegisterLock.isEngaged()) finish()
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    private fun tryUnlock() {
        val pin = binding.lockPin.text?.toString().orEmpty()
        if (!store.checkPin(pin)) {
            binding.lockPinError.setText(R.string.pin_wrong)
            binding.lockPinError.visibility = View.VISIBLE
            return
        }
        RegisterLock.clearFromPin()
        val settings = store.current()
        val seq = RegisterLock.pendingClearSeq()
        finish()
        thread {
            ShopClient.setLocked(settings, locked = false, lockSeq = seq)
        }
    }

    companion object {
        @Volatile
        var instance: RegisterLockActivity? = null

        private var lastShowAt = 0L

        fun show(context: Context) {
            if (!RegisterLock.isEngaged()) return
            val current = instance
            if (
                current != null &&
                !current.isFinishing &&
                current.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            ) {
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (current != null && now - lastShowAt < 400L) return
            lastShowAt = now
            val intent = Intent(context, RegisterLockActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            val service = TillAccessibilityService.instance
            try {
                if (service != null) {
                    service.bringLockForward(intent)
                } else {
                    context.startActivity(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun hide() {
            instance?.let { activity ->
                activity.runOnUiThread {
                    if (!activity.isFinishing) activity.finish()
                }
            }
        }
    }
}
