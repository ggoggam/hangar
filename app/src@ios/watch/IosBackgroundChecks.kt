package dev.ggoggam.hangar.app.watch

import androidx.compose.runtime.Composable

/** Not yet on iOS, so the settings leave the checks out. */
class IosBackgroundChecks : BackgroundChecks {
    override val supported = false

    override fun schedule() {}

    override fun cancel() {}
}

class IosNotifier : Notifier {
    override fun post(notification: HangarNotification) {}
}

@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit = { it(false) }
