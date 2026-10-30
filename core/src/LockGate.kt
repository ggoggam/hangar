package dev.ggoggam.hangar.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When the app lock holds the app shut: from a cold start while it's [on], and again when the app
 * comes back after [AWAY_LIMIT] or more in the background, so a trip to the browser to sign in or to
 * copy something doesn't ask again. It lives as long as the process, so an Android activity
 * recreated for a rotation finds it as it was.
 *
 * Times are the wall clock's: a monotonic clock stops while the device sleeps, so a phone left
 * overnight would open without asking. A clock that went backwards counts as the time having passed.
 * Called on the main thread.
 */
class LockGate(
    on: Boolean,
) {
    /** Whether the lock is turned on at all. */
    var on: Boolean = on
        private set

    private val _locked = MutableStateFlow(on)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _shuts = MutableStateFlow(0)

    /** Counts each time it shuts, already shut or not, so the lock screen asks once each time. */
    val shuts: StateFlow<Int> = _shuts.asStateFlow()

    private var awaySince: Instant? = null
    private var asking = false

    /** The app went to the background. Not while the system's prompt is up, which on Android can stop the app. */
    fun stopped(now: Instant) {
        if (!asking) awaySince = now
    }

    /** The app came back to the foreground, which shuts it if it was away long enough. */
    fun started(now: Instant) {
        val since = awaySince ?: return
        awaySince = null
        if (on && (now < since || now - since >= AWAY_LIMIT)) {
            _locked.value = true
            _shuts.value++
        }
    }

    /** Opens once [authenticate] succeeds; whether it did. */
    suspend fun unlock(authenticate: suspend () -> Boolean): Boolean = ask(authenticate).also { if (it) _locked.value = false }

    /** Turns the lock on once [authenticate] succeeds, leaving the app open; whether it did. */
    suspend fun turnOn(authenticate: suspend () -> Boolean): Boolean = ask(authenticate).also { if (it) on = true }

    fun turnOff() {
        on = false
        _locked.value = false
    }

    /** Opens without asking, while there's nothing to protect: signed out, or no way left to check who it is. */
    fun open() {
        awaySince = null
        _locked.value = false
    }

    private suspend fun ask(authenticate: suspend () -> Boolean): Boolean {
        asking = true
        return try {
            authenticate()
        } finally {
            asking = false
            awaySince = null
        }
    }

    companion object {
        /** How long the app can be in the background and come back still open. */
        val AWAY_LIMIT = 60.seconds
    }
}
