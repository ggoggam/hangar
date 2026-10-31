package dev.ggoggam.hangar.app.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.core.DEMO_TOKEN
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyLogin
import dev.ggoggam.hangar.core.FlyLoginException
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyToken
import dev.ggoggam.hangar.core.PendingLogin
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class LoginProblem { Expired, BadCode, BadToken, Unavailable, Network }

data class LoginState(
    /** Waiting for the code from this login's approval page, or null before one starts. */
    val pending: PendingLogin? = null,
    /** Pasting a token instead of signing in with the browser. */
    val tokenEntry: Boolean = false,
    val busy: Boolean = false,
    val problem: LoginProblem? = null,
)

class LoginViewModel(
    private val login: FlyLogin,
    private val discharges: FlyDischarges,
    private val http: HttpClient,
    private val session: FlySession,
    private val sessionName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private val _signedIn = Channel<Unit>(Channel.CONFLATED)

    /** Each sign-in that finished, for adding an account, which closes when one does. */
    val signedIn: Flow<Unit> = _signedIn.receiveAsFlow()

    /** Starts a login and hands its approval page to [open]. */
    fun start(open: (String) -> Unit) =
        run {
            val pending = login.start(sessionName)
            _state.update { it.copy(pending = pending) }
            open(pending.approvalUrl)
        }

    fun redeem(code: String) {
        val pending = _state.value.pending ?: return
        if (code.isBlank()) return
        run { finish(login.redeem(pending, code), live = true) }
    }

    fun useToken(raw: String) {
        val token = FlyToken(raw)
        if (token.isBlank) return
        // Check it before keeping it: a typo would otherwise sign in and fail on the next screen.
        run {
            val renewed = discharges.refresh(token, Clock.System.now().epochSeconds)
            FlyApi(http, renewed).overview()
            finish(renewed, live = true)
        }
    }

    /** Signs in to the demo, which answers from sample data built into the app. */
    fun tryDemo() = run { finish(DEMO_TOKEN, live = false) }

    fun showTokenEntry(on: Boolean) = _state.update { LoginState(tokenEntry = on) }

    fun startOver() = _state.update { LoginState() }

    /** Signs in with [token], named for the account switcher when it's a [live] account. */
    private suspend fun finish(
        token: FlyToken,
        live: Boolean,
    ) {
        val label = if (live) label(token) else null
        _state.value = LoginState()
        session.signIn(token, label)
        _signedIn.trySend(Unit)
    }

    /**
     * What the account switcher calls [token]'s account: its email, or for a token without a user,
     * such as an organization's read-only one, the organization's name. Null when neither loads,
     * which doesn't stop the sign-in.
     */
    private suspend fun label(token: FlyToken): String? {
        val api = FlyApi(http, token)
        return attempt { api.whoami().ifEmpty { null } }
            ?: attempt { api.organizations().firstOrNull()?.let { it.name ?: it.slug } }
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val problem =
                try {
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlyLoginException.Expired) {
                    _state.update { it.copy(pending = null) }
                    LoginProblem.Expired
                } catch (e: FlyLoginException.Rejected) {
                    LoginProblem.BadCode
                } catch (e: FlyLoginException.Unavailable) {
                    LoginProblem.Unavailable
                } catch (e: FlyApiException) {
                    LoginProblem.BadToken
                } catch (e: Exception) {
                    LoginProblem.Network
                }
            _state.update { it.copy(busy = false, problem = problem) }
        }
    }
}
