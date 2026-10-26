package dev.ggoggam.hangar.app.session

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.ggoggam.hangar.app.di.SESSION_NAME
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android's platform services, for [dev.ggoggam.hangar.app.di.initHangar]. */
fun androidPlatformModule(context: Context): Module =
    module {
        single<TokenStore> { AndroidTokenStore(context) }
        single(SESSION_NAME) {
            // The name the user gave the phone ("Galaxy A23"), else its model number.
            val device = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
            "Hangar on $device"
        }
    }
