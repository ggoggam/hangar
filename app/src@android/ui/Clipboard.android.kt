package dev.ggoggam.hangar.app.ui

import android.content.ClipData
import android.os.Build
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))

// Android 13 shows its own overlay for every copy.
internal actual val systemConfirmsCopy: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
