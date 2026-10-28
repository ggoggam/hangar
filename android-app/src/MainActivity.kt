package dev.ggoggam.hangar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.ggoggam.hangar.app.App
import dev.ggoggam.hangar.app.watch.openNotificationLink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again when the activity is recreated, say for a rotation, with the same intent.
        if (savedInstanceState == null) openNotificationLink(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openNotificationLink(intent)
    }
}
