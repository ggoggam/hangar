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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Where the tokens live between launches: the Android Keystore or the iOS Keychain. It holds one
 * string, [FlySession]'s accounts as JSON, or a single token from before there were accounts.
 */
interface TokenStore {
    fun read(): String?

    fun write(token: String)

    fun clear()
}

/** A signed-in Fly.io account, as the account switcher lists it. Its token stays in [FlySession]. */
data class FlyAccount(
    val id: String,
    /** The account's email, or for a token without a user, its first organization; null until known. */
    val label: String?,
    val demo: Boolean,
    /** The account the screens are showing. */
    val current: Boolean,
)

/**
 * The signed-in accounts, the one the screens are showing ([token], or null when signed out), and
 * the one place its discharges are renewed. Only the current account's discharges are renewed:
 * the others' run out while they wait, and are renewed when they're switched to.
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
    /** Every account with its token, and which is current. Changes on [scope]'s dispatcher, as [token] does. */
    private var saved = SavedAccounts.decode(store.read())

    private val _token = MutableStateFlow(saved.currentToken())
    val token: StateFlow<FlyToken?> = _token.asStateFlow()

    private val _accounts = MutableStateFlow(saved.listing())

    /** Every signed-in account, in the order they signed in. */
    val accounts: StateFlow<List<FlyAccount>> = _accounts.asStateFlow()

    private val lock = Mutex()

    /** The renewal under way and the token it started from. Guarded by [lock]. */
    private var renewal: Pair<FlyToken, Deferred<FlyToken?>>? = null

    /**
     * Signs in with [token] and makes it the current account. Signing in to an account that's
     * already here, by the same token or the same [label], renews its token rather than adding it again.
     */
    fun signIn(
        token: FlyToken,
        label: String? = null,
    ) {
        val same =
            saved.accounts.firstOrNull { FlyToken(it.token).identity == token.identity } ?: label?.let { l ->
                saved.accounts.firstOrNull { it.label == l && !FlyToken(it.token).isDemo }
            }
        val account = SavedAccount(same?.id ?: randomToken(9), token.value, label ?: same?.label)
        val accounts = if (same == null) saved.accounts + account else saved.accounts.map { if (it.id == same.id) account else it }
        save(SavedAccounts(account.id, accounts))
    }

    /** Signs the current account out, and switches to the next one if there is one. */
    fun signOut() {
        _token.value?.let(::end)
    }

    /** Makes the account [id] the current one. */
    fun switchTo(id: String) {
        if (saved.accounts.any { it.id == id }) save(saved.copy(current = id))
    }

    /** Keeps [label] for the account [id], for one that signed in before accounts had them. */
    fun setLabel(
        id: String,
        label: String,
    ) {
        if (saved.accounts.none { it.id == id && it.label != label }) return
        save(saved.copy(accounts = saved.accounts.map { if (it.id == id) it.copy(label = label) else it }))
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
        } else if (_token.value == current) {
            // Only over the token it started from: a sign-out, new sign-in or switch meanwhile stays.
            save(saved.copy(accounts = saved.accounts.map { if (it.token == current.value) it.copy(token = renewed.value) else it }))
        }
        return _token.value
    }

    /**
     * Signs out if [rejected] is still the token, switching to the next account; a newer token, or
     * another account's, stays.
     */
    private fun end(rejected: FlyToken): Boolean {
        if (_token.value != rejected) return false
        val accounts = saved.accounts.filterNot { it.token == rejected.value }
        save(SavedAccounts(accounts.firstOrNull()?.id, accounts))
        return true
    }

    private fun save(accounts: SavedAccounts) {
        saved = accounts
        if (accounts.accounts.isEmpty()) store.clear() else store.write(accounts.encode())
        _token.value = accounts.currentToken()
        _accounts.value = accounts.listing()
    }
}

@Serializable
private data class SavedAccount(
    val id: String,
    val token: String,
    val label: String? = null,
)

/** What [TokenStore] holds: every account, and which is current. */
@Serializable
private data class SavedAccounts(
    val current: String? = null,
    val accounts: List<SavedAccount> = emptyList(),
) {
    fun currentToken(): FlyToken? = accounts.firstOrNull { it.id == current }?.let { FlyToken(it.token) }?.takeUnless { it.isBlank }

    fun listing(): List<FlyAccount> = accounts.map { FlyAccount(it.id, it.label, FlyToken(it.token).isDemo, it.id == current) }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** [stored] as written by [encode], or a lone token from before accounts, or nothing. */
        fun decode(stored: String?): SavedAccounts {
            val text = stored?.trim().orEmpty()
            if (text.isEmpty()) return SavedAccounts()
            if (!text.startsWith("{")) {
                val token = FlyToken(text).takeUnless { it.isBlank } ?: return SavedAccounts()
                val id = randomToken(9)
                return SavedAccounts(id, listOf(SavedAccount(id, token.value)))
            }
            return runCatching { JSON.decodeFromString(serializer(), text) }.getOrDefault(SavedAccounts())
        }
    }
}
