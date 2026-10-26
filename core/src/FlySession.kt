package dev.ggoggam.hangar.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Where the token lives between launches: the Android Keystore or the iOS Keychain. */
interface TokenStore {
    fun read(): String?

    fun write(token: String)

    fun clear()
}

/**
 * The signed-in token, or null when signed out, and the one place its discharges are renewed.
 *
 * Screens call Fly.io several at a time (a dashboard starts seven loads at once), and when the
 * discharges run out they all need them renewed at the same moment. Renewal is single-flight: the
 * first caller starts it and the rest wait for the same outcome, so seven calls make one round
 * trip, and a renewal that fails fails them all once instead of being tried again by each in turn.
 */
class FlySession(
    private val store: TokenStore,
    private val discharges: FlyDischarges,
    /**
     * Runs renewals, so one keeps going for the callers waiting on it when the caller that started
     * it is cancelled. The token changes on its dispatcher.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val _token = MutableStateFlow(store.read()?.let(::FlyToken)?.takeUnless { it.isBlank })
    val token: StateFlow<FlyToken?> = _token.asStateFlow()

    private val lock = Mutex()

    /** The renewal under way and the token it started from. Guarded by [lock]. */
    private var renewal: Pair<FlyToken, Deferred<FlyToken?>>? = null

    fun signIn(token: FlyToken) {
        store.write(token.value)
        _token.value = token
    }

    fun signOut() {
        store.clear()
        _token.value = null
    }

    /**
     * The token to call Fly.io with: the stored one, with its discharges renewed first when they're
     * about to run out (they last minutes). Null when signed out, including when Fly.io won't renew
     * them any more. A renewal that fails for any other reason hands back the old token and lets
     * the call itself fail.
     */
    suspend fun fresh(): FlyToken? =
        try {
            renew(rejected = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _token.value
        }

    /**
     * [call] with a [fresh] token. When Fly.io turns that token down, its discharges are renewed
     * even though they look good, since the phone's clock may be off, and [call] runs once more.
     * Throws [FlyApiException.Unauthorized] only when the session is over: signed out already, or
     * Fly.io turning down the renewed token too, which signs out.
     */
    suspend fun <T> authorized(call: suspend (FlyToken) -> T): T {
        // Not fresh(), which shrugs off a failed renewal: one forced by another call that was turned
        // down means this token will be turned down too, so fail rather than force another.
        val token = renew(rejected = null) ?: throw FlyApiException.Unauthorized()
        try {
            return call(token)
        } catch (e: FlyApiException.Unauthorized) {
            // Renew and try again below.
        }
        val renewed = renew(rejected = token) ?: throw FlyApiException.Unauthorized()
        try {
            return call(renewed)
        } catch (e: FlyApiException.Unauthorized) {
            if (end(renewed)) throw e
            // Replaced while this call was out, by a renewal or a new sign-in: not this session's end.
            throw FlyApiException.Failed("token replaced during the call")
        }
    }

    /**
     * The token with its discharges renewed, joining a renewal already under way for it rather
     * than starting a second. [rejected] is a token Fly.io has just turned down: renew it even though
     * its discharges look good, unless it's been replaced already, in which case the replacement
     * is the answer.
     */
    private suspend fun renew(rejected: FlyToken?): FlyToken? {
        val pending =
            lock.withLock {
                val current = _token.value ?: return null
                if (rejected != null && current != rejected) return current
                renewal?.takeIf { (from, job) -> from == current && job.isActive }?.second
                    ?: run {
                        // A renewal only starts when there's something to renew, so a rejected
                        // token that joins one gets new discharges from it.
                        if (rejected == null && current.undischarged(nowSeconds()).isEmpty()) return current
                        scope
                            .async { renewNow(current, force = rejected != null) }
                            .also { renewal = current to it }
                    }
            }
        return pending.await()
    }

    /** Renews [current]'s discharges and keeps the result, then hands back the session's token. */
    private suspend fun renewNow(
        current: FlyToken,
        force: Boolean,
    ): FlyToken? {
        val renewed =
            try {
                discharges.refresh(current, nowSeconds(), force)
            } catch (e: FlyApiException.Unauthorized) {
                end(current)
                return _token.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A network failure, not Fly.io's answer: keep the session. A forced renewal has no
                // token worth calling with, so the calls waiting on it fail.
                if (force) throw e
                return _token.value
            }
        if (force && renewed == current) {
            // Nothing to renew, such as a pasted org token: the rejection stands.
            end(current)
        } else if (_token.compareAndSet(current, renewed)) {
            // Only over the token it started from: a sign-out or new sign-in meanwhile stays.
            store.write(renewed.value)
        }
        return _token.value
    }

    /** Signs out if [rejected] is still the token; a newer one, or another account's, stays. */
    private fun end(rejected: FlyToken): Boolean {
        if (!_token.compareAndSet(rejected, null)) return false
        store.clear()
        return true
    }
}
