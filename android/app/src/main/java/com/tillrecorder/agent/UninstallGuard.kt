package com.tillrecorder.agent

import android.app.admin.DeviceAdminReceiver

/** Optional device-admin flag. It does not lock the phone or erase data. */
class UninstallGuard : DeviceAdminReceiver()
