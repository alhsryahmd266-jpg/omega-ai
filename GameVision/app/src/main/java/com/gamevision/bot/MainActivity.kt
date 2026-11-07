package com.gamevision.bot

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import com.gamevision.bot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnLaunchOverlay.setOnClickListener {
            val svc = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        binding.stepOverlayStatus.text = if (hasOverlay) "✅ مفعّل" else "❌ لسه محتاج تفعيل"
        binding.stepAccessStatus.text = if (hasAccessibility) "✅ مفعّلة" else "❌ لسه محتاجة تفعيل"
        binding.btnLaunchOverlay.isEnabled = hasOverlay && hasAccessibility
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, CaptureAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .asSequence().any { it.equals(expected, ignoreCase = true) }
    }

    private fun TextUtils.SimpleStringSplitter.asSequence(): Sequence<String> = sequence {
        while (hasNext()) yield(next())
    }
}
