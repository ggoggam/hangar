package dev.ggoggam.hangar.app.session

import android.content.Context
import android.os.Build
import dev.ggoggam.hangar.app.di.SESSION_NAME
import dev.ggoggam.hangar.app.settings.SettingsStore
import dev.ggoggam.hangar.app.watch.AndroidBackgroundChecks
import dev.ggoggam.hangar.app.watch.AndroidNotifier
import dev.ggoggam.hangar.app.watch.BackgroundChecks
import dev.ggoggam.hangar.app.watch.Notifier
import dev.ggoggam.hangar.core.TokenStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android's platform services, for [dev.ggoggam.hangar.app.di.initHangar]. [notificationIcon] is the
 * status bar icon's drawable, which lives with the app's other icons.
 */
fun androidPlatformModule(
    context: Context,
    notificationIcon: Int,
): Module =
    module {
        single<TokenStore> { AndroidTokenStore(context) }
        single<SettingsStore> {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            object : SettingsStore {
                override fun read(key: String) = prefs.getString(key, null)

                override fun write(
                    key: String,
                    value: String,
                ) = prefs.edit().putString(key, value).apply()
            }
        }
        single<BackgroundChecks> { AndroidBackgroundChecks(context) }
        single<Notifier> { AndroidNotifier(context, notificationIcon) }
        // The model ("SM-A235F"), not the name the user gave the phone, which often has their own
        // name in it: Fly.io shows this on the approval page and in its token list.
        single(SESSION_NAME) { "Hangar on ${Build.MODEL}" }
    }
