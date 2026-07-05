package com.moneymanager.compose.filepicker

import androidx.compose.runtime.Composable

/**
 * A picked folder. [ref] is what the platform backend can open later (a JVM absolute path, or an
 * Android document-tree URI), [displayName] is human-readable (on JVM it is the path itself).
 */
data class PickedFolder(
    val ref: String,
    val displayName: String,
)

/**
 * Creates and remembers a folder (directory) picker launcher. [onResult] receives the chosen folder,
 * or null if cancelled. Used to configure a local import directory.
 */
@Composable
expect fun rememberFolderPicker(onResult: (PickedFolder?) -> Unit): FolderPickerLauncher

/**
 * Whether the platform can also accept a hand-typed folder path. True on JVM (any readable path
 * works); false on Android, where folders are only reachable through a SAF document-tree grant.
 */
expect val manualFolderEntrySupported: Boolean

expect class FolderPickerLauncher {
    fun launch()
}
