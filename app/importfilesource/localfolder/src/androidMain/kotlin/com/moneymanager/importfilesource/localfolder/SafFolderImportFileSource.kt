package com.moneymanager.importfilesource.localfolder

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Lists and reads files from a Storage Access Framework document tree. [folderUri] is either the
 * tree URI returned by OpenDocumentTree (a top-level import directory) or a document-in-tree URI
 * produced by [listSubfolders] (a discovered subfolder) — both carry the persisted tree grant, so
 * subfolders need no permission of their own.
 *
 * The file ref is the child's document id (stable while the file keeps its name/location — the same
 * semantics as the filename refs used by [LocalFolderImportFileSource] on the JVM); the subfolder
 * ref is a full document-in-tree URI so it can be reopened through the factory on its own.
 */
class SafFolderImportFileSource(
    private val contentResolver: ContentResolver,
    folderUri: String,
) : ImportFileSource {
    private val uri: Uri = Uri.parse(folderUri)

    // A tree URI has no document segment, so getDocumentId throws; fall back to the tree's own root.
    private val folderDocumentId: String =
        runCatching { DocumentsContract.getDocumentId(uri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(uri) }

    override suspend fun list(): List<ImportFileEntry> =
        withContext(Dispatchers.IO) {
            queryChildren()
                .filter { !it.isDirectory }
                .map { child ->
                    ImportFileEntry(
                        ref = child.documentId,
                        name = child.displayName,
                        lastModifiedEpochMs = child.lastModifiedEpochMs,
                        sizeBytes = child.sizeBytes,
                    )
                }.sortedBy { it.name }
        }

    override suspend fun listSubfolders(): List<ImportSubfolder> =
        withContext(Dispatchers.IO) {
            queryChildren()
                .filter { it.isDirectory }
                .map { child ->
                    ImportSubfolder(
                        ref = DocumentsContract.buildDocumentUriUsingTree(uri, child.documentId).toString(),
                        name = child.displayName,
                    )
                }.sortedBy { it.name }
        }

    override suspend fun download(fileRef: String): ByteArray =
        withContext(Dispatchers.IO) {
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, fileRef)
            translateRevokedGrant {
                contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: throw FileNotFoundException("Cannot open file in import directory: $fileUri")
            }
        }

    private data class ChildDocument(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val lastModifiedEpochMs: Long?,
        val sizeBytes: Long?,
    )

    // Child queries ignore selection args on most providers, so file/folder filtering is client-side.
    private fun queryChildren(): List<ChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, folderDocumentId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val cursor =
            translateRevokedGrant { contentResolver.query(childrenUri, projection, null, null, null) }
                ?: throw FileNotFoundException("Import directory does not exist (or its provider is gone): $uri")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChildDocument(
                            documentId = c.getString(0),
                            displayName = c.getString(1) ?: c.getString(0),
                            isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            lastModifiedEpochMs = c.getLongOrNullAt(3)?.takeIf { it > 0 },
                            sizeBytes = c.getLongOrNullAt(4)?.takeIf { it >= 0 },
                        ),
                    )
                }
            }
        }
    }

    // A revoked (or never-granted) persisted permission surfaces as SecurityException; rethrow with a
    // message the scan-failure UI can show, telling the user how to recover.
    private inline fun <T> translateRevokedGrant(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw IOException(
                "Access to this folder was revoked — delete the import directory and pick the folder again.",
                e,
            )
        }

    companion object {
        /** True when [ref] is a SAF document/tree URI rather than a plain filesystem path. */
        fun isDocumentTreeRef(ref: String): Boolean = ref.startsWith("content://")
    }
}

private fun Cursor.getLongOrNullAt(index: Int): Long? = if (isNull(index)) null else getLong(index)
