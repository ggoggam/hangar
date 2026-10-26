package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

// NSURLSession's default configuration keeps responses in the shared URLCache, on disk, and
// cookies in the app's cookie store. The API needs neither: every call carries its token.
internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        engine {
            configureSession {
                URLCache = null
                requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                HTTPCookieStorage = null
                HTTPShouldSetCookies = false
            }
        }
        config()
    }
