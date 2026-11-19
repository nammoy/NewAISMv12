package com.app.aisheildme.namtran.data.repository

import android.content.Context
import android.net.Uri
import com.app.aisheildme.namtran.core.common.AppException
import com.app.aisheildme.namtran.domain.model.DocumentCategory
import com.app.aisheildme.namtran.domain.model.DocumentItem
import com.app.aisheildme.namtran.domain.model.FolderCategory
import com.app.aisheildme.namtran.negotiator.Negotiator
import com.app.aisheildme.namtran.negotiator.Purpose
import com.app.aisheildme.namtran.negotiator.Requester
import com.app.aisheildme.namtran.vault.CreateFolder
import com.app.aisheildme.namtran.vault.DeleteDocument
import com.app.aisheildme.namtran.vault.ListDocuments
import com.app.aisheildme.namtran.vault.ListFolders
import com.app.aisheildme.namtran.vault.ReadDocument
import com.app.aisheildme.namtran.vault.StoreDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folders and documents, stored encrypted in the vault on this phone. Every call goes
 * through the [Negotiator]; document contents need a recent vault unlock.
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val negotiator: Negotiator,
    @param:ApplicationContext private val context: Context,
) {

    suspend fun getCategories(page: Int = 1, limit: Int = 20): List<DocumentCategory> =
        negotiator.request(Requester.Documents, Purpose.OwnerView, ListFolders(page, limit))

    suspend fun createCategory(
        name: String,
        category: FolderCategory,
    ): String {
        negotiator.request(
            Requester.Documents,
            Purpose.OwnerEdit,
            CreateFolder(folderName = name, icon = category.name.lowercase(), color = category.colorHex),
        )
        return "Folder created"
    }

    suspend fun getDocuments(
        categoryId: Int? = null,
        page: Int = 1,
        limit: Int = 20,
    ): List<DocumentItem> =
        negotiator.request(Requester.Documents, Purpose.OwnerView, ListDocuments(categoryId, page, limit))

    suspend fun deleteDocument(id: Int): String {
        negotiator.request(Requester.Documents, Purpose.OwnerEdit, DeleteDocument(id))
        return "Document deleted"
    }

    /** Reads the picked file and stores it encrypted in the vault (an Ingest request). */
    suspend fun uploadDocument(
        title: String,
        uri: Uri,
        fileName: String,
        fileType: String,
        categoryId: Int? = null,
        description: String? = null,
    ): String {
        val bytes = withContext(Dispatchers.IO) {
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (size > MAX_BYTES) throw AppException("That file is too large (max 25 MB).")
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AppException("Could not read the selected file.")
        }
        negotiator.request(
            Requester.Documents,
            Purpose.Ingest,
            StoreDocument(
                title = title,
                description = description?.takeIf { it.isNotBlank() },
                fileName = fileName,
                fileType = fileType,
                folderId = categoryId,
                bytes = bytes,
            ),
        )
        return "Saved to your vault"
    }

    /** Decrypts a document into app-private cache so a viewer app can open it. */
    suspend fun decryptForViewing(id: Int): Pair<DocumentItem, File> =
        decryptTo(id, Purpose.OwnerView, VIEW_DIR)

    /**
     * Decrypts a document for sharing outside the vault. The caller must have shown the
     * user what is leaving and got their go-ahead; the Negotiator records it as a Share.
     */
    suspend fun decryptForSharing(id: Int): Pair<DocumentItem, File> =
        decryptTo(id, Purpose.Share, SHARE_DIR)

    private suspend fun decryptTo(id: Int, purpose: Purpose, dir: String): Pair<DocumentItem, File> {
        val content = negotiator.request(Requester.Documents, purpose, ReadDocument(id))
        val file = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, dir).apply { mkdirs() }
            val safeName = content.item.fileName.ifBlank { content.item.title }
                .replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(' ', '_').ifBlank { "document" }
            File(folder, safeName).apply { writeBytes(content.bytes) }
        }
        return content.item to file
    }

    companion object {
        const val VIEW_DIR = "documents"
        const val SHARE_DIR = "exports"
        private const val MAX_BYTES = 25L * 1024 * 1024

        /** Removes decrypted copies left from earlier viewing or sharing. Call at app start. */
        fun wipeDecryptedCopies(context: Context) {
            File(context.cacheDir, VIEW_DIR).deleteRecursively()
            File(context.cacheDir, SHARE_DIR).deleteRecursively()
        }
    }
}
