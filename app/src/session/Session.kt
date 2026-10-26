package dev.ggoggam.hangar.app.session

import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** The signed-in token, or null when signed out. */
class Session(
    private val store: TokenStore,
    private val discharges: FlyDischarges,
) {
    private val _token = MutableStateFlow(store.read()?.let(::FlyToken)?.takeUnless { it.isBlank })
    val token: StateFlow<FlyToken?> = _token.asStateFlow()

    private val refreshing = Mutex()

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
        refreshing.withLock {
            val current = _token.value ?: return null
            val renewed =
                try {
                    discharges.refresh(current, Clock.System.now().epochSeconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlyApiException.Unauthorized) {
                    if (_token.value == current) signOut()
                    return null
                } catch (e: Exception) {
                    return current
                }
            // Signed out or in as someone else while renewing: don't bring the old token back.
            if (renewed !== current && _token.value == current) signIn(renewed)
            _token.value
        }
}
