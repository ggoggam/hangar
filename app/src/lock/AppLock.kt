package dev.ggoggam.hangar.app.lock

import dev.ggoggam.hangar.app.session.DeviceAuth
import dev.ggoggam.hangar.app.settings.SettingsStore
import dev.ggoggam.hangar.core.LockGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * The app lock: whether it's on, kept between launches, and whether it holds the app shut now,
 * kept as long as the process ([LockGate]). Only whether it's on is written down; the system checks
 * the face, fingerprint or passcode and only tells the app whether that worked.
 */
class AppLock(
    private val store: SettingsStore,
) {
    private val gate = LockGate(store.read(KEY_ON)?.toBooleanStrictOrNull() ?: false)

    private val _on = MutableStateFlow(gate.on)

    /** Off until the user turns it on, which asks them to authenticate first. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** Whether the lock screen covers the app. */
    val locked: StateFlow<Boolean> = gate.locked

    /** Counts each time it locks, so the lock screen asks once each time. */
    val locks: StateFlow<Int> = gate.shuts

    /** Turns the lock on if [auth] confirms it's the device's owner; whether it did. */
    suspend fun turnOn(
        auth: DeviceAuth,
        reason: String,
    ): Boolean {
        if (!gate.turnOn { auth.authenticate(reason) }) return false
        store.write(KEY_ON, "true")
        _on.value = true
        return true
    }

    /** Turns the lock off. The app is open to get to the switch, so this doesn't ask. */
    fun turnOff() {
        gate.turnOff()
        store.write(KEY_ON, "false")
        _on.value = false
    }

    /** Opens the app if [auth] confirms it's the device's owner; whether it did. */
    suspend fun unlock(
        auth: DeviceAuth,
        reason: String,
    ): Boolean = gate.unlock { auth.authenticate(reason) }

    /** Opens the app without asking, while there's nothing to protect or no way left to ask. */
    fun open() = gate.open()

    /** The app went to the background. */
    fun stopped() = gate.stopped(Clock.System.now())

    /** The app came back, which locks it again after a minute or more away. */
    fun started() = gate.started(Clock.System.now())

    private companion object {
        const val KEY_ON = "lock.on"
    }
}
