package expo.modules.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** After a reboot, brings the floating button back if the user left it on. */
class GvrBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (GvrBubbleService.wasOn(context) && Settings.canDrawOverlays(context)) {
            GvrBubbleService.start(context)
        }
    }
}
