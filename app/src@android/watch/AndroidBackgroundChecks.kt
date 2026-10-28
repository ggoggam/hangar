package dev.ggoggam.hangar.app.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ggoggam.hangar.app.generated.resources.Res
import dev.ggoggam.hangar.app.generated.resources.settings_checks_detail
import org.jetbrains.compose.resources.StringResource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel as SystemChannel

/**
 * [WatchWorker] every 15 minutes, WorkManager's shortest period, whenever there's a network. Doze
 * and battery saver can hold it back, so it's "about" every 15 minutes.
 */
class AndroidBackgroundChecks(
    private val context: Context,
) : BackgroundChecks {
    override val supported = true

    override fun schedule() {
        val request =
            PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    internal companion object {
        const val WORK = "watch"
    }
}

/** One [Watcher] run, which WorkManager starts. Stops the checks once there's nothing to check. */
class WatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val watcher: Watcher by inject()

    override suspend fun doWork(): Result {
        if (!watcher.run()) WorkManager.getInstance(applicationContext).cancelUniqueWork(AndroidBackgroundChecks.WORK)
        return Result.success()
    }
}

/** Notifications that open the app at their link when tapped, with [icon] in the status bar. */
class AndroidNotifier(
    private val context: Context,
    private val icon: Int,
) : Notifier {
    override fun post(notification: HangarNotification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Creating a channel that exists only updates its name, for a change of language.
        manager.createNotificationChannel(
            SystemChannel(notification.channel.id, notification.channelName, NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (!manager.areNotificationsEnabled()) return
        // Each key its own request code, so one notification's link doesn't replace another's.
        val open =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                notification.link?.let { link -> it.putExtra(EXTRA_LINK, link.encode()) }
                PendingIntent.getActivity(
                    context,
                    notification.key.hashCode(),
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        val built =
            Notification
                .Builder(context, notification.channel.id)
                .setSmallIcon(icon)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setStyle(Notification.BigTextStyle().bigText(notification.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        // Tagged with the key, so the same problem's notification replaces the last one.
        manager.notify(notification.key, 0, built)
    }
}

private const val EXTRA_LINK = "dev.ggoggam.hangar.link"

/**
 * Hands the link of the notification that started or resumed the activity with [intent] to the app.
 * One relaunched from Recents carries the old notification's extras too, so it's ignored.
 */
fun openNotificationLink(intent: Intent?) {
    if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
    KoinPlatform.getKoin().get<NotificationLinks>().open(intent.getStringExtra(EXTRA_LINK))
    intent.removeExtra(EXTRA_LINK)
}

@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.invoke(granted)
            pending = null
        }
    return rememberUpdatedState { onResult: (Boolean) -> Unit ->
        // Android 13 and later ask; before that, notifications are on unless turned off in settings.
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        } else {
            pending = onResult
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.value
}

internal actual val checksDetail: StringResource = Res.string.settings_checks_detail
