package com.moneymanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.localsettings.JvmLocalSettings
import com.moneymanager.localsettings.KEY_LAST_DIRECTORY
import com.moneymanager.localsettings.LocalSettings
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Paths

private const val DATABASE_EXTENSION = ".db"

private val logger = logging()

actual class DatabaseLocationPickerLauncher(
    private val onResult: (DbLocation?) -> Unit,
    private val localSettings: LocalSettings,
) {
    actual fun launch(mode: DatabasePickerMode) {
        onResult(showDialog(mode))
    }

    private fun showDialog(mode: DatabasePickerMode): DbLocation? {
        val frame = Frame()
        try {
            val isCreate = mode == DatabasePickerMode.CREATE
            val fileDialog =
                FileDialog(
                    frame,
                    if (isCreate) "Create database" else "Open database",
                    if (isCreate) FileDialog.SAVE else FileDialog.LOAD,
                )
            localSettings.getString(KEY_LAST_DIRECTORY)?.let { fileDialog.directory = it }
            fileDialog.setFilenameFilter { _, name -> name.lowercase().endsWith(DATABASE_EXTENSION) }
            fileDialog.isVisible = true

            val directory = fileDialog.directory
            val file = fileDialog.file
            if (directory == null || file == null) return null

            localSettings.putString(KEY_LAST_DIRECTORY, directory)
            val fileName =
                if (isCreate && !file.lowercase().endsWith(DATABASE_EXTENSION)) file + DATABASE_EXTENSION else file
            val resolved = Paths.get(directory, fileName)
            val parent = resolved.parent
            logger.info {
                "DB picker [$mode]: dialog.directory=[$directory] dialog.file=[$file] -> resolved=[$resolved] " +
                    "parent=[$parent] parentExists=${parent?.let { Files.exists(it) }} " +
                    "parentWritable=${parent?.let { Files.isWritable(it) }}"
            }
            return DbLocation(resolved)
        } finally {
            frame.dispose()
        }
    }
}

@Composable
actual fun rememberDatabaseLocationPicker(onResult: (DbLocation?) -> Unit): DatabaseLocationPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onResult) {
        DatabaseLocationPickerLauncher(
            onResult = { result -> scope.launch { onResult(result) } },
            localSettings = JvmLocalSettings(),
        )
    }
}
