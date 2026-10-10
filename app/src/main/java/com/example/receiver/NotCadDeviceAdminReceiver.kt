package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Play Store Compliant Device Administrator receiver.
 * Enables hardware lockNow() on session expiration if explicitly activated by the user.
 */
class NotCadDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Focus screen lock permission enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Focus screen lock permission disabled", Toast.LENGTH_SHORT).show()
    }
}
