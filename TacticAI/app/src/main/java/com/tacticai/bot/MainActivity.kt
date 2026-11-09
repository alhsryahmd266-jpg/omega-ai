package com.tacticai.bot

import android.content.ColorStateList
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.tacticai.bot.databinding.ActivityMainBinding

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
        binding.btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        setChip(binding.chipOverlay, hasOverlay)
        setChip(binding.chipAccess, hasAccessibility)
        binding.btnLaunchOverlay.isEnabled = hasOverlay && hasAccessibility
    }

    private fun setChip(chip: Chip, ok: Boolean) {
        chip.text = if (ok) "مفعّل" else "غير مفعّل"
        val bg = if (ok) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorErrorContainer
        val fg = if (ok) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnErrorContainer
        chip.chipBackgroundColor = ColorStateList.valueOf(resolveAttrColor(bg))
        chip.setTextColor(resolveAttrColor(fg))
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return ContextCompat.getColor(this, tv.resourceId)
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
