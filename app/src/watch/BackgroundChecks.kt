package dev.ggoggam.hangar.app.watch

import androidx.compose.runtime.Composable
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.isDemo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource

/** Runs [Watcher] now and then while the app isn't open: WorkManager on Android, BGTaskScheduler on iOS. */
interface BackgroundChecks {
    /** Whether this platform can check in the background at all. */
    val supported: Boolean

    /** Starts the checks, or leaves them be if they're already scheduled. */
    fun schedule()

    fun cancel()
}

/** Shows notifications from the device itself. */
interface Notifier {
    fun post(notification: HangarNotification)
}

class HangarNotification(
    /** The same for the same problem, so a later notification replaces the earlier one. */
    val key: String,
    val channel: NotificationChannel,
    /** [channel]'s name, which Android shows in the app's notification settings. */
    val channelName: String,
    val title: String,
    val body: String,
    /** Where tapping it goes; null opens the app as it was left. */
    val link: NotificationLink? = null,
)

/** A screen a notification opens, which travels through the system as [encode]'s text. */
@Serializable
sealed interface NotificationLink {
    /** The app list, with Fly.io's incidents and each organization's usage above it. */
    @Serializable
    @SerialName("apps")
    data object Apps : NotificationLink

    /** One app's dashboard on [tab], one of [dev.ggoggam.hangar.app.dashboard.DashboardTab]'s names. */
    @Serializable
    @SerialName("app")
    data class App(
        val name: String,
        val org: String,
        val tab: String,
    ) : NotificationLink

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        /** The link [encode] wrote, or null for anything else. */
        fun decode(text: String?): NotificationLink? = text?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The link of the notification the user last tapped, from the platform to the app's navigation,
 * which [take]s it once it can open it: straight away if the app is open, or after it starts.
 */
class NotificationLinks {
    private val _pending = MutableStateFlow<NotificationLink?>(null)
    val pending: StateFlow<NotificationLink?> = _pending.asStateFlow()

    /** Called with a tapped notification's link text; anything that isn't one is ignored. */
    fun open(encoded: String?) {
        NotificationLink.decode(encoded)?.let { _pending.value = it }
    }

    /** The pending link, now handled. */
    fun take(): NotificationLink? = _pending.getAndUpdate { null }
}

/** The kinds of notification, each of which the user can mute on Android. */
enum class NotificationChannel(
    val id: String,
) {
    Incidents("incidents"),
    Apps("apps"),
    Billing("billing"),
    Deploys("deploys"),
    Volumes("volumes"),
    Account("account"),
}

/**
 * Keeps the checks scheduled while they're on and someone is signed in, outside the demo, and
 * cancelled otherwise. Runs for as long as the app is open, where both of those change.
 */
suspend fun keepScheduled(
    session: FlySession,
    settings: WatchSettings,
    checks: BackgroundChecks,
) {
    if (!checks.supported) return
    combine(session.token.map { it != null && !it.isDemo }, settings.enabled) { signedIn, on -> signedIn && on }
        .distinctUntilChanged()
        .collect { if (it) checks.schedule() else checks.cancel() }
}

/**
 * Asks to post notifications, if the system needs asking, and calls back with whether it may. The
 * returned function is safe to call again: it asks the system only while the answer isn't known.
 */
@Composable
expect fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit

/** What the switch in Settings says about how often the checks run, which differs by platform. */
internal expect val checksDetail: StringResource
