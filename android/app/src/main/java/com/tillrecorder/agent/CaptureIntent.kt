package com.tillrecorder.agent

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun captureIntentForWholeScreen(manager: MediaProjectionManager): Intent {
    return manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
}
