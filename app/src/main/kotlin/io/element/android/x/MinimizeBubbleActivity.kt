package io.element.android.x

import android.app.Activity
import android.os.Bundle

class MinimizeBubbleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.e("BubbleDebug", "MinimizeBubbleActivity created, stealing focus...")
        
        val uriToOpen = intent.getStringExtra("EXTRA_URI")
        
        // Use a tiny delay to ensure the OS registers the focus change before we finish
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (uriToOpen != null) {
                try {
                    val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uriToOpen))
                    browserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    android.util.Log.e("BubbleDebug", "Failed to launch browser", e)
                }
            }

            android.util.Log.e("BubbleDebug", "MinimizeBubbleActivity finishing now.")
            finish()
            overridePendingTransition(0, 0)
        }, 100)
    }
}
