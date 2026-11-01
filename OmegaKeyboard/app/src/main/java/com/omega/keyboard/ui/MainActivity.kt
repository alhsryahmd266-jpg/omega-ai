package com.omega.keyboard.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Temporary placeholder; the real setup wizard replaces it. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Omega Keyboard"
            textSize = 24f
            setPadding(48, 96, 48, 48)
        })
    }
}
