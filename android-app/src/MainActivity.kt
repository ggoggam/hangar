package dev.ggoggam.hangar

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.ggoggam.hangar.app.App
import dev.ggoggam.hangar.app.watch.openNotificationLink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge: Android 15 and later always are, and the theme makes the bars transparent
        // before that. SystemBars matches their icons to the app's theme.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) WindowCompat.setDecorFitsSystemWindows(window, false)
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
