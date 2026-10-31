package dev.ggoggam.hangar.app.lock

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.session.rememberDeviceAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Covers the signed-in app while [lock] holds it shut. It asks as soon as the app is in front, once
 * each time it locks, and Unlock asks again after a cancel. With no screen lock left on the device
 * there's no way to ask, so it opens: removing the screen lock took the passcode already.
 */
@Composable
private fun LockScreen(
    lock: AppLock,
    modifier: Modifier = Modifier,
) {
    val auth = rememberDeviceAuth()
    val reason = stringResource(Res.string.lock_reason_unlock)
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val unlock: () -> Unit = {
        if (!auth.available) {
            lock.open()
        } else if (!asking) {
            asking = true
            scope.launch {
                try {
                    lock.unlock(auth, reason)
                } finally {
                    asking = false
                }
            }
        }
    }
    val locks by lock.locks.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Not on every resume: the prompt itself pauses the app, and a cancel would ask again at once.
    LaunchedEffect(locks, lifecycle) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        unlock()
    }
    // Last in, so it comes before the screens' own: back does nothing rather than pop them unseen.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = {})
    // A Surface takes the touches, so none reach the app underneath.
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(
                Lucide.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displayLarge)
            Text(
                stringResource(Res.string.lock_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = unlock, enabled = !asking, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(Res.string.lock_unlock))
            }
        }
    }
}

/**
 * [content], the signed-in app, under the lock screen while [lock] holds it shut. The app stays
 * composed, so its back stack and loaded screens survive, but out of reach: hidden from
 * accessibility, with no focus or touches, and back doesn't pop its screens unseen. Counts the time
 * in the background while it's composed, so only while signed in: the trip to the browser to sign
 * in doesn't count.
 */
@Composable
fun LockedBy(
    lock: AppLock,
    content: @Composable () -> Unit,
) {
    // Not with the lifecycle: back in front, the lock has to cover the first frame.
    val locked by lock.locked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lock, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> lock.stopped()
                    Lifecycle.Event.ON_START -> lock.started()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Nor a keyboard left open over the lock screen.
    val focus = LocalFocusManager.current
    LaunchedEffect(locked) { if (locked) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { onEnter = { if (locked) cancelFocusChange() } }
                .focusGroup(),
        ) {
            content()
        }
        if (locked) LockScreen(lock)
    }
}
