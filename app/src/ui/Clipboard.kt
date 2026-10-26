package dev.ggoggam.hangar.app.ui

import androidx.compose.ui.platform.ClipEntry

/** [text] as a plain-text clipboard entry. */
internal expect fun plainTextClip(text: String): ClipEntry

/** Whether the system shows its own confirmation on copy, so the app shouldn't show a second one. */
internal expect val systemConfirmsCopy: Boolean
