package dev.ggoggam.hangar.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ggoggam.hangar.core.FlyActionException
import dev.ggoggam.hangar.core.FlyApi
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyAppDetail
import dev.ggoggam.hangar.core.FlyAppMetrics
import dev.ggoggam.hangar.core.FlyCertificate
import dev.ggoggam.hangar.core.FlyHostIssue
import dev.ggoggam.hangar.core.FlyIp
import dev.ggoggam.hangar.core.FlyLogEntry
import dev.ggoggam.hangar.core.FlyLogs
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlyMachineActions
import dev.ggoggam.hangar.core.FlyMachines
import dev.ggoggam.hangar.core.FlyMetrics
import dev.ggoggam.hangar.core.FlyRelease
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyToken
import dev.ggoggam.hangar.core.FlyVolume
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A section of the dashboard that loads on its own, so one failing call doesn't blank the rest. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Ready<T>(
        val value: T,
    ) : Load<T>

    data object Failed : Load<Nothing>
}

val <T> Load<T>.value: T? get() = (this as? Load.Ready)?.value

/**
 * Runs [fetch] with [session]'s token and stores its result in [state] with [set]. A failed reload
 * keeps what was already shown, and a session that's over changes nothing, since the login screen
 * is up.
 */
internal fun <S, T> CoroutineScope.loadInto(
    state: MutableStateFlow<S>,
    session: FlySession,
    fetch: suspend (FlyToken) -> T,
    get: (S) -> Load<T>,
    set: (S, Load<T>) -> S,
): Job =
    launch {
        val result =
            try {
                Load.Ready(session.authorized(fetch))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlyApiException.Unauthorized) {
                return@launch
            } catch (e: Exception) {
                Load.Failed
            }
        state.update { s -> if (result is Load.Failed && get(s) is Load.Ready) s else set(s, result) }
    }

enum class MetricsWindow(
    val seconds: Long,
) {
    Hour(3_600),
    SixHours(6 * 3_600),
    Day(24 * 3_600),
}

data class DashboardState(
    val app: Load<FlyAppDetail> = Load.Loading,
    val hostIssues: Load<List<FlyHostIssue>> = Load.Loading,
    val machines: Load<List<FlyMachine>> = Load.Loading,
    val releases: Load<List<FlyRelease>> = Load.Loading,
    val volumes: Load<List<FlyVolume>> = Load.Loading,
    val ips: Load<List<FlyIp>> = Load.Loading,
    val certificates: Load<List<FlyCertificate>> = Load.Loading,
    val metrics: Load<FlyAppMetrics> = Load.Loading,
    val window: MetricsWindow = MetricsWindow.Hour,
    val refreshing: Boolean = false,
    /** The machines an action is under way on, by id. */
    val busy: Map<String, Action> = emptyMap(),
    /** The app's restart while it's under way. */
    val appRestart: AppRestart? = null,
)

/** What the dashboard can do to a machine, or to the whole app. */
enum class Action {
    Start,
    Stop,
    Suspend,
    Restart,
    RestartApp,
}

/** An app restart's progress: [done] of [total] running machines restarted so far. */
data class AppRestart(
    val done: Int = 0,
    val total: Int = 0,
)

/** How an action went, for the screen to say once. [target] is the machine's name, or the app's. */
sealed interface ActionNotice {
    val action: Action
    val target: String

    data class Done(
        override val action: Action,
        override val target: String,
    ) : ActionNotice

    data class Failed(
        override val action: Action,
        override val target: String,
        val reason: ActionFailure,
        /** Fly.io's own words, for [ActionFailure.Other] when it said something. */
        val detail: String? = null,
    ) : ActionNotice
}

enum class ActionFailure {
    NotAllowed,
    Busy,
    PostgresApp,
    TimedOut,
    Other,
}

data class LogsState(
    val entries: List<FlyLogEntry> = emptyList(),
    /** The machine the logs are narrowed to, or null for all of them. */
    val instance: String? = null,
    val failing: Boolean = false,
)

/**
 * One app's dashboard: `fly status`, `fly incidents hosts list`, `fly machines list`, `fly releases`
 * and `fly logs` in one place.
 */
class AppDashboardViewModel(
    val appName: String,
    private val org: String,
    private val http: HttpClient,
    private val session: FlySession,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val _logs = MutableStateFlow(LogsState())
    val logs: StateFlow<LogsState> = _logs.asStateFlow()

    private val _notices = Channel<ActionNotice>(Channel.BUFFERED)

    /** Each action's outcome, once. */
    val notices: Flow<ActionNotice> = _notices.receiveAsFlow()

    private var logCursor: String? = null
    private var metricsJob: Job? = null

    init {
        // Quiet, since every section shows its own placeholder until it arrives.
        refresh(quiet = true)
    }

    /** Loads every section again; [quiet] keeps the pull-to-refresh spinner hidden, for timed reloads. */
    fun refresh(quiet: Boolean = false) {
        if (session.token.value == null) return
        if (!quiet) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            loadAll().forEach { it.join() }
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Every section at once; the session renews the token once for all of them when it's due. */
    private fun loadAll(): List<Job> =
        listOf(
            load({ FlyApi(http, it).app(appName) }, { it.app }) { s, v -> s.copy(app = v) },
            load({ FlyApi(http, it).hostIssues(appName) }, { it.hostIssues }) { s, v -> s.copy(hostIssues = v) },
            load({ FlyMachines(http, it).machines(appName) }, { it.machines }) { s, v -> s.copy(machines = v) },
            load({ FlyApi(http, it).releases(appName) }, { it.releases }) { s, v -> s.copy(releases = v) },
            load({ FlyMachines(http, it).volumes(appName) }, { it.volumes }) { s, v -> s.copy(volumes = v) },
            load({ FlyMachines(http, it).ips(appName) }, { it.ips }) { s, v -> s.copy(ips = v) },
            load({ FlyMachines(http, it).certificates(appName) }, { it.certificates }) { s, v -> s.copy(certificates = v) },
            loadMetrics(),
        )

    fun setWindow(window: MetricsWindow) {
        if (window == _state.value.window) return
        _state.update { it.copy(window = window, metrics = Load.Loading) }
        loadMetrics()
    }

    /** Loads the metrics for the current window, dropping a load still running for another one. */
    private fun loadMetrics(): Job {
        val window = _state.value.window
        metricsJob?.cancel()
        return load(
            { FlyMetrics(http, it).dashboard(org, appName, Clock.System.now().epochSeconds, window.seconds) },
            { it.metrics },
        ) { s, v ->
            s.copy(metrics = v)
        }.also { metricsJob = it }
    }

    private fun <T> load(
        fetch: suspend (FlyToken) -> T,
        get: (DashboardState) -> Load<T>,
        set: (DashboardState, Load<T>) -> DashboardState,
    ) = viewModelScope.loadInto(_state, session, fetch, get, set)

    /**
     * Starts, stops, suspends or restarts [machine], as `fly machine` does, then loads the dashboard
     * again. One action at a time per machine.
     */
    fun act(
        machine: FlyMachine,
        action: Action,
    ) {
        if (machine.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + (machine.id to action)) }
        viewModelScope.launch {
            val notice =
                perform(action, machine.name.ifEmpty { machine.id }) { actions ->
                    when (action) {
                        Action.Start -> actions.startMachine(appName, machine.id)
                        Action.Stop -> actions.stopMachine(appName, machine.id)
                        Action.Suspend -> actions.suspendMachine(appName, machine.id)
                        Action.Restart -> actions.restartMachine(appName, machine.id)
                        Action.RestartApp -> error("not a machine's action")
                    }
                }
            _state.update { it.copy(busy = it.busy - machine.id) }
            finish(notice)
        }
    }

    /**
     * Restarts the app's running machines, as `fly apps restart` does: [rolling], one after another
     * as each passes its checks, or all at once.
     */
    fun restartApp(rolling: Boolean) {
        if (_state.value.appRestart != null) return
        _state.update { it.copy(appRestart = AppRestart()) }
        viewModelScope.launch {
            val notice =
                perform(Action.RestartApp, appName) { actions ->
                    actions.restartApp(appName, rolling) { done, total -> _state.update { it.copy(appRestart = AppRestart(done, total)) } }
                }
            _state.update { it.copy(appRestart = null) }
            finish(notice)
        }
    }

    /** [block] with the session's token, and how it went. Null when the session is over. */
    private suspend fun perform(
        action: Action,
        target: String,
        block: suspend (FlyMachineActions) -> Unit,
    ): ActionNotice? {
        fun failed(
            reason: ActionFailure,
            detail: String? = null,
        ) = ActionNotice.Failed(action, target, reason, detail)
        return try {
            session.authorized { block(FlyMachineActions(http, it)) }
            ActionNotice.Done(action, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FlyApiException.Unauthorized) {
            null
        } catch (e: FlyActionException.NotAllowed) {
            failed(ActionFailure.NotAllowed)
        } catch (e: FlyActionException.Busy) {
            failed(ActionFailure.Busy)
        } catch (e: FlyActionException.PostgresApp) {
            failed(ActionFailure.PostgresApp)
        } catch (e: FlyActionException.TimedOut) {
            failed(ActionFailure.TimedOut)
        } catch (e: FlyApiException.Failed) {
            failed(ActionFailure.Other, e.message)
        } catch (e: Exception) {
            failed(ActionFailure.Other)
        }
    }

    /** Says how it went and shows where it left the app. */
    private suspend fun finish(notice: ActionNotice?) {
        notice?.let { _notices.send(it) }
        loadAll().forEach { it.join() }
    }

    /** Narrows the logs to one machine, or all of them for null, starting over from the latest. */
    fun setLogInstance(instance: String?) {
        if (instance == _logs.value.instance) return
        logCursor = null
        _logs.value = LogsState(instance = instance)
    }

    /**
     * Polls for new log lines until cancelled, as `fly logs` does without NATS: straight on while
     * pages come back full, then backing off to a few seconds when it's quiet.
     */
    suspend fun followLogs() {
        var idle = 1_000L
        while (true) {
            val instance = _logs.value.instance
            try {
                val page = session.authorized { FlyLogs(http, it).page(appName, logCursor, instance) }
                if (instance != _logs.value.instance) continue
                logCursor = page.cursor
                _logs.update { s ->
                    val seen = s.entries.mapTo(HashSet()) { it.id }
                    // add() also drops a line repeated within the page: Fly.io sometimes sends one twice,
                    // and the list keys lines by id.
                    val fresh = page.entries.filter { it.id.isEmpty() || seen.add(it.id) }
                    s.copy(entries = (s.entries + fresh).takeLast(MAX_LOG_LINES), failing = false)
                }
                if (page.entries.isNotEmpty()) {
                    idle = 1_000L
                    delay(250)
                } else {
                    delay(idle)
                    idle = (idle * 2).coerceAtMost(4_000L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlyApiException.Unauthorized) {
                return
            } catch (e: Exception) {
                _logs.update { it.copy(failing = true) }
                delay(5_000)
            }
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 1_000
    }
}
