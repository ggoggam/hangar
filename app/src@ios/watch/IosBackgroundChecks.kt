package dev.ggoggam.hangar.app.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ggoggam.hangar.app.generated.resources.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.mp.KoinPlatform
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * A BGAppRefreshTask, asked for no sooner than 15 minutes after the last. iOS decides when it
 * really runs, from how often the app is used: a few times a day for an app opened daily, rarely
 * for one that isn't, and never after the user force-quits it, until it's opened again.
 */
class IosBackgroundChecks : BackgroundChecks {
    override val supported = true

    @OptIn(ExperimentalForeignApi::class)
    override fun schedule() {
        val request = BGAppRefreshTaskRequest(TASK)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(15 * 60.0)
        // Turned down in the simulator, which has no background refresh, and when the user has
        // turned Background App Refresh off; there's nothing to do about either here.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    override fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK)
    }

    internal companion object {
        /** Listed under BGTaskSchedulerPermittedIdentifiers in `ios-app/src/Info.plist`. */
        const val TASK = "dev.ggoggam.hangar.watch"
    }
}

/**
 * Registers the handler for [IosBackgroundChecks]' task, which iOS requires before the app finishes
 * launching. Call after [dev.ggoggam.hangar.app.di.initHangar]. Each run asks for the next first,
 * so a run cut short doesn't end the checks, and stops asking once there's nothing to check.
 */
fun registerBackgroundChecks() {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(IosBackgroundChecks.TASK, usingQueue = null) { task: BGTask? ->
        task ?: return@registerForTaskWithIdentifier
        val koin = KoinPlatform.getKoin()
        val checks = koin.get<BackgroundChecks>()
        checks.schedule()
        val run =
            CoroutineScope(Dispatchers.Main).launch {
                val more =
                    try {
                        koin.get<Watcher>().run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        true
                    }
                if (!more) checks.cancel()
                task.setTaskCompletedWithSuccess(true)
            }
        // iOS gives the task about 30 seconds; past that, stop and say so.
        task.expirationHandler = {
            if (run.isActive) {
                run.cancel()
                task.setTaskCompletedWithSuccess(false)
            }
        }
    }
}

/**
 * Local notifications, grouped by [NotificationChannel] in Notification Center. One with the key of
 * a notification still showing replaces it.
 */
class IosNotifier : Notifier {
    override fun post(notification: HangarNotification) {
        val content =
            UNMutableNotificationContent().apply {
                setTitle(notification.title)
                setBody(notification.body)
                setThreadIdentifier(notification.channel.id)
                setSound(UNNotificationSound.defaultSound)
            }
        val request = UNNotificationRequest.requestWithIdentifier(notification.key, content, trigger = null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
    }
}

@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit =
    remember {
        { onResult ->
            // Asks only the first time; after that it answers with the user's choice in Settings.
            UNUserNotificationCenter
                .currentNotificationCenter()
                .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                    dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
                }
        }
    }

internal actual val checksDetail: StringResource = Res.string.settings_checks_detail_ios
