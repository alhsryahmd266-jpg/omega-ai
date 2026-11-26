package com.omega.sight.testgame

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** The stand-in game the CI emulator run uses to test Omega Sight (never part of the release APK). */
class GameActivity : Activity() {
    private lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // draw under the camera cut-out too: otherwise a black strip would sit there, outside the game
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        game = GameView(this)
        game.autoFire = intent?.getBooleanExtra("autofire", true) ?: true
        setContentView(game)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        game.running = true
        game.postInvalidateOnAnimation()
    }

    override fun onPause() {
        game.running = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
