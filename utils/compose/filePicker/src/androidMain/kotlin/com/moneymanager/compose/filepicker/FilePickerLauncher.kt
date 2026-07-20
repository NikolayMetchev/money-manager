package com.moneymanager.compose.filepicker

actual class FilePickerLauncher(
    private val mimeTypes: Array<String>,
    private val launcher: (Array<String>) -> Unit,
) {
    actual fun launch() {
        launcher(mimeTypes)
    }
}

actual class MultipleFilePickerLauncher(
    private val mimeTypes: Array<String>,
    private val launcher: (Array<String>) -> Unit,
) {
    actual fun launch() {
        launcher(mimeTypes)
    }
}

actual class BinaryFilePickerLauncher(
    private val mimeTypes: Array<String>,
    private val launcher: (Array<String>) -> Unit,
) {
    actual fun launch() {
        launcher(mimeTypes)
    }
}
