package io.element.android.x

import android.app.Activity
import android.os.Bundle

class MinimizeBubbleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("BubbleDebug", "MinimizeBubbleActivity created, stealing focus...")
        
        // Use a tiny delay to ensure the OS registers the focus change before we finish
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.util.Log.e("BubbleDebug", "MinimizeBubbleActivity finishing now.")
            finish()
            overridePendingTransition(0, 0)
        }, 100)
    }
}
