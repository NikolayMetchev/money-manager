@file:OptIn(ExperimentalWasmJsInterop::class)

package com.moneymanager.compose.filepicker

import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Instant

/** A file the person picked, read whole: [text] or [bytes], as the picker asked. */
private external interface BrowserFile : JsAny {
    val name: String
    val lastModified: Double
    val text: String?
    val bytes: Int8Array?
}

/**
 * Opens the browser's file chooser for [mimeTypes] and reads what the person picks. [onPicked] gets the files, or
 * none if they cancelled.
 */
internal fun pickBrowserFiles(
    mimeTypes: List<String>,
    multiple: Boolean,
    binary: Boolean,
    onPicked: (List<PickedBrowserFile>) -> Unit,
) {
    val accept = (mimeTypes + mimeTypesToExtensions(mimeTypes)).joinToString(",")
    chooseFiles(accept, multiple, binary) { files ->
        onPicked(
            (0 until files.length).mapNotNull { files[it] }.map { file ->
                PickedBrowserFile(
                    name = file.name,
                    lastModified =
                        file.lastModified
                            .toLong()
                            .takeIf { it > 0 }
                            ?.let(Instant::fromEpochMilliseconds),
                    text = file.text,
                    bytes = file.bytes?.toByteArray(),
                )
            },
        )
    }
}

internal class PickedBrowserFile(
    val name: String,
    val lastModified: Instant?,
    val text: String?,
    val bytes: ByteArray?,
)

// The parameters are read by the JavaScript. The chooser fires `cancel` when dismissed, and `change` with the files.
@Suppress("UnusedParameter")
private fun chooseFiles(
    accept: String,
    multiple: Boolean,
    binary: Boolean,
    onPicked: (JsArray<BrowserFile>) -> Unit,
): Unit =
    js(
        """{
        const input = document.createElement("input");
        input.type = "file";
        input.accept = accept;
        input.multiple = multiple;
        let done = false;
        const finish = (files) => { if (!done) { done = true; onPicked(files); } };
        input.addEventListener("cancel", () => finish([]));
        input.addEventListener("change", () => {
            Promise.all(Array.from(input.files || []).map((file) =>
                (binary ? file.arrayBuffer().then((buffer) => new Int8Array(buffer)) : file.text()).then((content) => ({
                    name: file.name,
                    lastModified: file.lastModified,
                    text: binary ? null : content,
                    bytes: binary ? content : null,
                }))))
                .then(finish, () => finish([]));
        });
        input.click();
    }""",
    )

/** Offers [content] to the browser as a download named [fileName]. */
@Suppress("UnusedParameter")
internal fun downloadText(
    fileName: String,
    content: String,
    mimeType: String,
): Unit =
    js(
        """{
        const url = URL.createObjectURL(new Blob([content], { type: mimeType }));
        const link = document.createElement("a");
        link.href = url;
        link.download = fileName;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    }""",
    )
