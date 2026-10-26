package dev.ggoggam.hangar.app.session

import android.app.Activity
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

@Composable
actual fun rememberSignInSheet(): SignInSheet {
    val context = LocalContext.current
    return remember(context) {
        object : SignInSheet {
            override fun open(url: String) {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(context, url.toUri())
            }
        }
    }
}
