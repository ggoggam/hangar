package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

// OkHttp keeps nothing on disk unless it's given a Cache or a CookieJar, and this one gets neither.
internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp, config)
