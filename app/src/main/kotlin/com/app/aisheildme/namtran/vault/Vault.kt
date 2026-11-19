package com.app.aisheildme.namtran.vault

import android.content.Context
import androidx.room.Room
import com.app.aisheildme.namtran.domain.model.PatientPrefill
import com.app.aisheildme.namtran.domain.model.PatientReview
import com.app.aisheildme.namtran.domain.model.SavedCheckInSummary
import com.app.aisheildme.namtran.vault.db.PatientProfileEntity
import com.app.aisheildme.namtran.vault.db.PatientReviewEntity
import com.app.aisheildme.namtran.domain.model.User
import com.app.aisheildme.namtran.negotiator.VaultPass
import com.app.aisheildme.namtran.vault.db.AccountEntity
import com.app.aisheildme.namtran.vault.db.AuditEntryEntity
import com.app.aisheildme.namtran.vault.db.CheckInSummaryEntity
import com.app.aisheildme.namtran.vault.db.SyncState
import com.app.aisheildme.namtran.vault.db.VaultDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * The data vault: the encrypted database and (from stage 3) encrypted files.
 *
 * - No network code lives here or anywhere it calls.
 * - Every entry point requires a [VaultPass], which only the Negotiator can create, so
 *   no screen or repository can bypass the Negotiator. This is checked at compile time.
 */
@Singleton
class Vault @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val db: VaultDatabase by lazy { open() }

    private fun open(): VaultDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = VaultKeyProvider(context).passphrase()
        return Room.databaseBuilder(context, VaultDatabase::class.java, VaultDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <R> execute(pass: VaultPass, accountId: Int?, op: VaultOp<R>): R =
        withContext(Dispatchers.IO) {
            val result: Any? = when (op) {
                is CreateAccount -> createAccount(op)
                is Authenticate -> authenticate(op)
                is AccountHasPin -> (accounts().byEmail(normalize(op.email)) ?: throw noLocalAccount()).pinHash != null
                is VerifyPinForReset -> verifyPinForReset(op)
                is ResetPassword -> resetPassword(op)

                GetProfile -> accounts().byId(requireAccount(accountId))?.let(::profileOf)
                is SaveProfile -> saveProfile(requireAccount(accountId), op.profile)
                is ChangePassword -> changePassword(requireAccount(accountId), op)
                is SetPin -> setPin(requireAccount(accountId), op.pin)
                is VerifyPin -> verifyPin(requireAccount(accountId), op.pin)
                is ChangePin -> changePin(requireAccount(accountId), op)
                DeleteAccount -> accounts().delete(requireAccount(accountId))

                is SaveCheckInSummary -> db.checkIns().save(op.summary.toEntity(requireAccount(accountId)))
                ListCheckInSummaries -> db.checkIns().all(requireAccount(accountId)).map { it.toDomain() }
                CountCheckInSummaries -> db.checkIns().count(requireAccount(accountId))
                is DeleteCheckInSummary -> db.checkIns().delete(requireAccount(accountId), op.id)
                ClearCheckInSummaries -> db.checkIns().clear(requireAccount(accountId))

                is SavePatientProfile -> savePatientProfile(requireAccount(accountId), op.profile)
                GetPatientProfile -> db.patientProfiles().get(requireAccount(accountId))
                    ?.let { json.decodeFromString(PatientPrefill.serializer(), it.payloadJson) }
                is SavePatientReview -> savePatientReview(requireAccount(accountId), op.review)
                LatestPatientReview -> db.patientReviews().latest(requireAccount(accountId))
                    ?.let { json.decodeFromString(PatientReview.serializer(), it.reviewJson) }
            }
            result as R
        }

    @Suppress("UNCHECKED_CAST")
    fun <R> observe(pass: VaultPass, accountId: Int, query: VaultQuery<R>): Flow<R> {
        val flow: Flow<Any?> = when (query) {
            ObserveProfile -> accounts().observe(accountId).map { it?.let(::profileOf) }
            ObserveCheckInSummaries -> db.checkIns().observeAll(accountId).map { rows -> rows.map { it.toDomain() } }
        }
        return (flow as Flow<R>).flowOn(Dispatchers.IO)
    }

    suspend fun audit(pass: VaultPass, entry: AuditEntryEntity) = withContext(Dispatchers.IO) {
        val id = db.audit().insert(entry)
        if (id % PRUNE_EVERY == 0L) db.audit().pruneBefore(entry.at - AUDIT_RETENTION_MS)
    }

    // ---- accounts ----

    private fun accounts() = db.accounts()

    private suspend fun createAccount(op: CreateAccount): User {
        val email = normalize(op.email)
        if (accounts().byEmail(email) != null) {
            throw VaultException("An account with this email already exists on this device.", VaultException.Kind.Conflict)
        }
        val now = System.currentTimeMillis()
        val salt = SecretHasher.newSalt()
        val draft = op.profile.copy(email = email, isEmailVerified = true, isPinSet = false, apiToken = "")
        val id = accounts().insert(
            AccountEntity(
                email = email,
                passwordSalt = salt,
                passwordHash = SecretHasher.hash(op.password, salt, SecretHasher.PASSWORD_ITERATIONS),
                passwordIterations = SecretHasher.PASSWORD_ITERATIONS,
                profileJson = json.encodeToString(User.serializer(), draft),
                createdAt = now,
                updatedAt = now,
            ),
        ).toInt()
        val saved = draft.copy(id = id)
        accounts().byId(id)?.let { accounts().update(it.copy(profileJson = encode(saved))) }
        return saved
    }

    private suspend fun authenticate(op: Authenticate): User {
        val account = accounts().byEmail(normalize(op.email)) ?: throw noLocalAccount()
        ensureNotLockedOut(account)
        val ok = SecretHasher.matches(op.password, account.passwordSalt, account.passwordIterations, account.passwordHash)
        recordAttempt(account, ok)
        if (!ok) throw VaultException(WRONG_CREDENTIALS)
        return profileOf(account)
    }

    private suspend fun verifyPinForReset(op: VerifyPinForReset) {
        val account = accounts().byEmail(normalize(op.email))
            ?: throw VaultException("No account for this email on this device.", VaultException.Kind.NotFound)
        checkPin(account, op.pin)
    }

    private suspend fun resetPassword(op: ResetPassword) {
        val account = accounts().byEmail(normalize(op.email))
            ?: throw VaultException("No account for this email on this device.", VaultException.Kind.NotFound)
        updatePassword(account, op.newPassword)
    }

    private suspend fun saveProfile(accountId: Int, profile: User): User {
        val account = accounts().byId(accountId) ?: throw notFound()
        val current = profileOf(account)
        // Identity and security flags are owned by the vault, not by the caller.
        val merged = profile.copy(
            id = accountId,
            email = account.email,
            apiToken = "",
            isEmailVerified = true,
            isPinSet = current.isPinSet,
        )
        accounts().update(account.copy(profileJson = encode(merged), updatedAt = now(), syncState = dirty(account.syncState)))
        return merged
    }

    private suspend fun changePassword(accountId: Int, op: ChangePassword) {
        val account = accounts().byId(accountId) ?: throw notFound()
        ensureNotLockedOut(account)
        val ok = SecretHasher.matches(op.current, account.passwordSalt, account.passwordIterations, account.passwordHash)
        recordAttempt(account, ok)
        if (!ok) throw VaultException("Your current password is incorrect.")
        updatePassword(accounts().byId(accountId) ?: throw notFound(), op.new)
    }

    private suspend fun updatePassword(account: AccountEntity, newPassword: String) {
        val salt = SecretHasher.newSalt()
        accounts().update(
            account.copy(
                passwordSalt = salt,
                passwordHash = SecretHasher.hash(newPassword, salt, SecretHasher.PASSWORD_ITERATIONS),
                passwordIterations = SecretHasher.PASSWORD_ITERATIONS,
                failedAttempts = 0,
                lockedUntil = 0,
                updatedAt = now(),
                syncState = dirty(account.syncState),
            ),
        )
    }

    private suspend fun setPin(accountId: Int, pin: String) {
        val account = accounts().byId(accountId) ?: throw notFound()
        val salt = SecretHasher.newSalt()
        val profile = profileOf(account).copy(isPinSet = true)
        accounts().update(
            account.copy(
                pinSalt = salt,
                pinHash = SecretHasher.hash(pin, salt, SecretHasher.PIN_ITERATIONS),
                profileJson = encode(profile),
                updatedAt = now(),
                syncState = dirty(account.syncState),
            ),
        )
    }

    private suspend fun verifyPin(accountId: Int, pin: String) {
        checkPin(accounts().byId(accountId) ?: throw notFound(), pin)
    }

    private suspend fun changePin(accountId: Int, op: ChangePin) {
        checkPin(accounts().byId(accountId) ?: throw notFound(), op.oldPin)
        setPin(accountId, op.newPin)
    }

    private suspend fun checkPin(account: AccountEntity, pin: String) {
        val salt = account.pinSalt
        val hash = account.pinHash
        if (salt == null || hash == null) {
            throw VaultException("No vault PIN has been set for this account.", VaultException.Kind.NotFound)
        }
        ensureNotLockedOut(account)
        val ok = SecretHasher.matches(pin, salt, SecretHasher.PIN_ITERATIONS, hash)
        recordAttempt(account, ok)
        if (!ok) throw VaultException("Incorrect PIN.")
    }

    // ---- patient profile & reviews ----

    private suspend fun savePatientProfile(accountId: Int, profile: PatientPrefill): String {
        val existing = db.patientProfiles().get(accountId)
        // A stable, local patient code; the server phase can map it to a remote one.
        val code = existing?.patientCode ?: "P-%05d".format(accountId)
        db.patientProfiles().save(
            PatientProfileEntity(
                accountId = accountId,
                patientCode = code,
                payloadJson = json.encodeToString(PatientPrefill.serializer(), profile.copy(patientCode = code)),
                updatedAt = now(),
                syncState = existing?.syncState?.let(::dirty) ?: SyncState.LOCAL,
                remoteId = existing?.remoteId,
            ),
        )
        return code
    }

    private suspend fun savePatientReview(accountId: Int, review: PatientReview) {
        val at = now()
        db.patientReviews().insert(
            PatientReviewEntity(
                accountId = accountId,
                patientCode = review.patient.patientCode.orEmpty(),
                reviewJson = json.encodeToString(PatientReview.serializer(), review),
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    // ---- brute-force protection (persisted, so restarting the app doesn't reset it) ----

    private fun ensureNotLockedOut(account: AccountEntity) {
        val remainingMs = account.lockedUntil - now()
        if (remainingMs > 0) {
            val seconds = (remainingMs + 999) / 1000
            throw VaultException(
                "Too many attempts. Try again in ${formatWait(seconds)}.",
                VaultException.Kind.LockedOut,
            )
        }
    }

    private suspend fun recordAttempt(account: AccountEntity, success: Boolean) {
        val fresh = accounts().byId(account.id) ?: return
        if (success) {
            if (fresh.failedAttempts != 0 || fresh.lockedUntil != 0L) {
                accounts().update(fresh.copy(failedAttempts = 0, lockedUntil = 0))
            }
            return
        }
        val failures = fresh.failedAttempts + 1
        val lockedUntil = if (failures >= FREE_ATTEMPTS) {
            val step = failures - FREE_ATTEMPTS
            now() + min(BASE_LOCKOUT_MS shl min(step, 10), MAX_LOCKOUT_MS)
        } else {
            0L
        }
        accounts().update(fresh.copy(failedAttempts = failures, lockedUntil = lockedUntil))
    }

    // ---- helpers ----

    private fun requireAccount(accountId: Int?): Int =
        accountId ?: throw VaultException("Please sign in first.", VaultException.Kind.Denied)

    private fun profileOf(account: AccountEntity): User =
        json.decodeFromString(User.serializer(), account.profileJson)
            .copy(id = account.id, email = account.email, isPinSet = account.pinHash != null, apiToken = "")

    private fun encode(user: User): String = json.encodeToString(User.serializer(), user)

    private fun normalize(email: String) = email.trim().lowercase()

    private fun dirty(state: SyncState) = if (state == SyncState.SYNCED) SyncState.DIRTY else state

    private fun notFound() = VaultException("Account not found.", VaultException.Kind.NotFound)

    /**
     * Accounts from the old online version lived on a server and don't exist in this
     * on-device vault, so say so plainly instead of "wrong password".
     */
    private fun noLocalAccount() = VaultException(
        "There's no account for this email on this phone yet. Accounts from the old " +
            "online version don't carry over — tap Sign up to create one (you can reuse " +
            "the same email and password).",
        VaultException.Kind.NotFound,
    )

    private fun now() = System.currentTimeMillis()

    private fun formatWait(seconds: Long): String =
        if (seconds < 60) "$seconds seconds" else "${(seconds + 59) / 60} minutes"

    private fun SavedCheckInSummary.toEntity(accountId: Int) = CheckInSummaryEntity(
        id = id,
        accountId = accountId,
        createdAt = createdAtEpochMs,
        summaryText = summaryText,
        patientName = patientName,
        clinicName = clinicName,
        appointmentType = appointmentType,
        reasonForVisit = reasonForVisit,
        deviceLabel = deviceLabel,
        updatedAt = now(),
    )

    private fun CheckInSummaryEntity.toDomain() = SavedCheckInSummary(
        id = id,
        createdAtEpochMs = createdAt,
        summaryText = summaryText,
        patientName = patientName.orEmpty(),
        clinicName = clinicName.orEmpty(),
        appointmentType = appointmentType.orEmpty(),
        reasonForVisit = reasonForVisit.orEmpty(),
        deviceLabel = deviceLabel,
    )

    private companion object {
        const val WRONG_CREDENTIALS = "Email or password is incorrect."
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCKOUT_MS = 30_000L
        const val MAX_LOCKOUT_MS = 15 * 60_000L
        const val PRUNE_EVERY = 200L
        const val AUDIT_RETENTION_MS = 180L * 24 * 60 * 60 * 1000
    }
}
