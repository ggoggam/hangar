package dev.ggoggam.hangar.app.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.app.session.Session
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyDischarges
import dev.ggoggam.hangar.core.FlyLogin
import dev.ggoggam.hangar.core.FlyLoginException
import dev.ggoggam.hangar.core.FlyToken
import dev.ggoggam.hangar.core.PendingLogin
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val session: Session,
    private val sessionName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

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
        run { finish(login.redeem(pending, code)) }
    }

    fun useToken(raw: String) {
        val token = FlyToken(raw)
        if (token.isBlank) return
        // Check it before keeping it: a typo would otherwise sign in and fail on the next screen.
        run {
            val renewed = discharges.refresh(token, Clock.System.now().epochSeconds)
            FlyApi(http, renewed).overview()
            finish(renewed)
        }
    }

    fun showTokenEntry(on: Boolean) = _state.update { LoginState(tokenEntry = on) }

    fun startOver() = _state.update { LoginState() }

    private fun finish(token: FlyToken) {
        _state.value = LoginState()
        session.signIn(token)
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
