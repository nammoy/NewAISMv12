package com.app.aisheildme.namtran.vault

import com.app.aisheildme.namtran.domain.model.DocumentCategory
import com.app.aisheildme.namtran.domain.model.DocumentItem
import com.app.aisheildme.namtran.domain.model.PatientPrefill
import com.app.aisheildme.namtran.domain.model.PatientReview
import com.app.aisheildme.namtran.domain.model.SavedCheckInSummary
import com.app.aisheildme.namtran.domain.model.User

/**
 * The complete vocabulary the Negotiator can speak to the Vault.
 *
 * Every operation is a plain data object (no lambdas, no live references), so the same
 * protocol can later be carried over on-device IPC (a bound service in a separate,
 * internet-less vault process) without changing the callers.
 *
 * Operations never name an account: the Negotiator attaches the signed-in account, so a
 * caller cannot reach another account's data.
 */
sealed interface VaultOp<R> {
    /** Operations allowed before anyone is signed in. */
    sealed interface Unauthenticated<R> : VaultOp<R>

    val name: String get() = this::class.simpleName ?: "Unknown"
}

/** Long-lived reads that stream updates. Same rules as [VaultOp]. */
sealed interface VaultQuery<R> {
    val name: String get() = this::class.simpleName ?: "Unknown"
}

// ---- Accounts (before sign-in) ----

data class CreateAccount(val email: String, val password: String, val profile: User) :
    VaultOp.Unauthenticated<User>

data class Authenticate(val email: String, val password: String) :
    VaultOp.Unauthenticated<User>

data class AccountHasPin(val email: String) : VaultOp.Unauthenticated<Boolean>

data class VerifyPinForReset(val email: String, val pin: String) : VaultOp.Unauthenticated<Unit>

/** Only honoured by the Negotiator after a successful [VerifyPinForReset]. */
data class ResetPassword(val email: String, val newPassword: String) :
    VaultOp.Unauthenticated<Unit>

// ---- Account (signed in) ----

data object GetProfile : VaultOp<User?>

data class SaveProfile(val profile: User) : VaultOp<User>

data class ChangePassword(val current: String, val new: String) : VaultOp<Unit>

data class SetPin(val pin: String) : VaultOp<Unit>

data class VerifyPin(val pin: String) : VaultOp<Unit>

data class ChangePin(val oldPin: String, val newPin: String) : VaultOp<Unit>

data object DeleteAccount : VaultOp<Unit>

data object ObserveProfile : VaultQuery<User?>

// ---- Check-in history ----

data class SaveCheckInSummary(val summary: SavedCheckInSummary) : VaultOp<Unit>

data object ListCheckInSummaries : VaultOp<List<SavedCheckInSummary>>

data object CountCheckInSummaries : VaultOp<Int>

data class DeleteCheckInSummary(val id: String) : VaultOp<Unit>

data object ClearCheckInSummaries : VaultOp<Unit>

data object ObserveCheckInSummaries : VaultQuery<List<SavedCheckInSummary>>

// ---- Patient check-in profile & reviews ----

/** Saves the profile used to pre-fill the next check-in; returns the patient code. */
data class SavePatientProfile(val profile: PatientPrefill) : VaultOp<String>

data object GetPatientProfile : VaultOp<PatientPrefill?>

data class SavePatientReview(val review: PatientReview) : VaultOp<Unit>

data object LatestPatientReview : VaultOp<PatientReview?>

// ---- Folders & documents ----

/** Document-content operations: the Negotiator only allows them while the vault is unlocked. */
sealed interface DocumentAccess

data class ListFolders(val page: Int, val limit: Int) : VaultOp<List<DocumentCategory>>

data class CreateFolder(val folderName: String, val icon: String, val color: String) : VaultOp<DocumentCategory>

data class ListDocuments(val folderId: Int?, val page: Int, val limit: Int) :
    VaultOp<List<DocumentItem>>, DocumentAccess

/**
 * Stores a new document. The bytes travel in the message; over future IPC this becomes a
 * file descriptor handed to the vault process.
 */
class StoreDocument(
    val title: String,
    val description: String?,
    val fileName: String,
    val fileType: String,
    val folderId: Int?,
    val bytes: ByteArray,
) : VaultOp<DocumentItem>, DocumentAccess

data class DeleteDocument(val id: Int) : VaultOp<Unit>, DocumentAccess

/** Decrypts a document for the owner to view, or for a consented share. */
data class ReadDocument(val id: Int) : VaultOp<DocumentContent>, DocumentAccess

class DocumentContent(val item: DocumentItem, val bytes: ByteArray)

/** Raised by the vault for expected failures (wrong password, locked out, …). */
class VaultException(message: String, val kind: Kind = Kind.Invalid) : Exception(message) {
    enum class Kind { Invalid, NotFound, LockedOut, Conflict, Denied }
}
