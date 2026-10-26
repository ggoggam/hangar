package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

// The JVM target only runs the tests, which hand the clients a MockEngine instead.
internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(config)
