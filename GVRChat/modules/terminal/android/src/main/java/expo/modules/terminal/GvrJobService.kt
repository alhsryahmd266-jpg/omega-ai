package expo.modules.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the app process alive (foreground priority) while a long terminal
 * command runs, so Android doesn't suspend/kill it the moment the screen
 * locks or the user switches apps (Doze, App Standby, the "phantom process
 * killer" on Android 12+). This is exactly why "apk add ..." or a big
 * "pip install" could otherwise die silently mid-way.
 *
 * targetSdk is 28 here, so the Android 10+ foregroundServiceType system
 * (and the Android 14 requirement to declare one) does not apply — the
 * plain pre-Q startForeground(id, notification) model is correct and
 * sufficient. See AndroidManifest.xml in this module for the <service>
 * declaration merged into the app manifest.
 */
class GvrJobService : Service() {

  companion object {
    private const val CHANNEL_ID = "gvr_jobs"
    private const val NOTIF_ID = 4871

    @Volatile private var running = false

    /** Shows the "GVR is running: <label>" notification (starts it if not already up). */
    fun start(context: Context, label: String) {
      try {
        val intent = Intent(context, GvrJobService::class.java).putExtra("label", label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        running = true
      } catch (e: Exception) {
        running = false
      }
    }

    /** Dismisses the notification and stops the service. Safe to call even if never started. */
    fun stop(context: Context) {
      if (!running) return
      running = false
      try { context.stopService(Intent(context, GvrJobService::class.java)) } catch (e: Exception) { }
    }
  }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(NotificationManager::class.java)
      if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(CHANNEL_ID, "مهام GVR الجارية", NotificationManager.IMPORTANCE_LOW)
        channel.description = "بتفضل الشاشة دي شغالة علشان الأمر الطويل في الترمنال يكمل من غير ما النظام يوقفه"
        channel.setShowBadge(false)
        mgr?.createNotificationChannel(channel)
      }
    }
  }

  private fun buildNotification(label: String): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("GVR شغال...")
      .setContentText(label)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val label = intent?.getStringExtra("label")?.takeIf { it.isNotBlank() } ?: "تنفيذ أمر"
    try {
      startForeground(NOTIF_ID, buildNotification(label))
    } catch (e: Exception) {
      // starting a foreground service can be refused in rare OEM/background-restricted
      // states; the command still runs, it just loses the extra protection.
      stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    running = false
    super.onDestroy()
  }
}
