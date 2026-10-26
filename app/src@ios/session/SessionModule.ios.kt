package dev.ggoggam.hangar.app.session

import dev.ggoggam.hangar.app.di.SESSION_NAME
import dev.ggoggam.hangar.core.TokenStore
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.UIKit.UIDevice

/** iOS's platform services, for [dev.ggoggam.hangar.app.di.initHangar]. [store] and [signIn] are the Swift bridges. */
fun iosPlatformModule(
    store: IosSecureStore,
    signIn: IosSignInBridge,
): Module =
    module {
        single<TokenStore> {
            object : TokenStore {
                override fun read() = store.read(KEY_TOKEN)

                override fun write(token: String) = store.write(KEY_TOKEN, token)

                override fun clear() = store.delete(KEY_TOKEN)
            }
        }
        single { signIn }
        // iOS doesn't tell apps the name the user gave the phone, only "iPhone" or "iPad".
        single(SESSION_NAME) { "Hangar on ${UIDevice.currentDevice.model}" }
    }

private const val KEY_TOKEN = "token"
