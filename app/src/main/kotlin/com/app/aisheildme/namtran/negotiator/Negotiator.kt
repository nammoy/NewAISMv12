package com.app.aisheildme.namtran.negotiator

import com.app.aisheildme.namtran.core.common.AppException
import com.app.aisheildme.namtran.data.local.SessionStore
import com.app.aisheildme.namtran.data.local.VaultLock
import com.app.aisheildme.namtran.domain.model.User
import com.app.aisheildme.namtran.vault.Authenticate
import com.app.aisheildme.namtran.vault.CreateAccount
import com.app.aisheildme.namtran.vault.DeleteAccount
import com.app.aisheildme.namtran.vault.DocumentAccess
import com.app.aisheildme.namtran.vault.GetProfile
import com.app.aisheildme.namtran.vault.ResetPassword
import com.app.aisheildme.namtran.vault.Vault
import com.app.aisheildme.namtran.vault.VaultException
import com.app.aisheildme.namtran.vault.VaultOp
import com.app.aisheildme.namtran.vault.VaultQuery
import com.app.aisheildme.namtran.vault.VerifyPinForReset
import com.app.aisheildme.namtran.vault.db.AuditEntryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proof that a vault call comes from the Negotiator. The interface is sealed, so only
 * code in this package can create one, and the only instance is private to this file.
 */
sealed interface VaultPass

private data object NegotiatorPass : VaultPass

/** Which part of the app is asking. Recorded in the audit log. */
enum class Requester { Auth, Profile, CheckIn, Documents, Records, Notifications, Connector, System }

/** Why it is asking. Drives the policy decision. */
enum class Purpose {
    /** Sign-up, sign-in and password recovery. */
    Authenticate,

    /** The signed-in owner looking at their own data on their own device. */
    OwnerView,

    /** The signed-in owner changing their own data. */
    OwnerEdit,

    /** New data entering the vault (scan, import, shared-in file, connector). */
    Ingest,

    /** Data leaving the vault to another app or person. Requires consent. */
    Share,
}

/**
 * The AI negotiator layer: the only path between the app and the data vault.
 *
 * For every request it:
 *  1. Resolves *who* is signed in (callers never name an account).
 *  2. Checks the request against policy (purpose, sign-in state, recovery grants).
 *  3. Records the decision in the vault's audit log.
 *  4. Forwards allowed requests to the vault over the [com.app.aisheildme.namtran.vault.VaultProtocol].
 *
 * Gemini Nano is used for the requests that need judgement: interpreting data coming in
 * ([Purpose.Ingest]) and minimising what goes out ([Purpose.Share]). Those AI steps are
 * added with the features that use them; reading your own data stays a fast rule check.
 *
 * Communication with the vault is on-device only. Nothing here or in the vault uses the
 * network, and the app ships without the INTERNET permission.
 */
@Singleton
class Negotiator @Inject constructor(
    private val vault: Vault,
    private val session: SessionStore,
    private val vaultLock: VaultLock,
) {

    private data class RecoveryGrant(val email: String, val expiresAt: Long)

    @Volatile
    private var recoveryGrant: RecoveryGrant? = null

    /** Streams the signed-in account id; null when signed out. */
    val signedInAccount: Flow<Int?> = session.accountId

    suspend fun <R> request(requester: Requester, purpose: Purpose, op: VaultOp<R>): R {
        val accountId = session.currentAccountId()
        val denial = policyDenial(accountId, purpose, op)
        if (denial != null) {
            audit(accountId, requester, purpose, op.name, Decision.Denied, denial)
            throw AppException(denial)
        }

        val result = try {
            vault.execute(NegotiatorPass, accountId, op)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            audit(accountId, requester, purpose, op.name, Decision.Failed, e.kind.name)
            throw AppException(e.message ?: AppException.GENERIC)
        }

        afterSuccess(op, result)
        audit(session.currentAccountId() ?: accountId, requester, purpose, op.name, Decision.Allowed, null)
        return result
    }

    /** A live view that follows whoever is signed in; emits null while signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> observe(requester: Requester, purpose: Purpose, query: VaultQuery<R>): Flow<R?> =
        session.accountId.flatMapLatest { accountId ->
            if (accountId == null) {
                flowOf(null)
            } else {
                vault.observe(NegotiatorPass, accountId, query)
                    .onStart { audit(accountId, requester, purpose, query.name, Decision.Allowed, null) }
            }
        }

    suspend fun signOut() {
        session.setAccountId(null)
        vaultLock.lock()
    }

    // ---- policy ----

    private suspend fun policyDenial(accountId: Int?, purpose: Purpose, op: VaultOp<*>): String? = when {
        op is VaultOp.Unauthenticated<*> && purpose != Purpose.Authenticate ->
            "This request isn't allowed here."

        op is ResetPassword && !hasRecoveryGrant(op.email) ->
            "Please verify your PIN again before setting a new password."

        op !is VaultOp.Unauthenticated<*> && accountId == null ->
            "Please sign in first."

        purpose == Purpose.Authenticate && op !is VaultOp.Unauthenticated<*> ->
            "This request isn't allowed here."

        op is DocumentAccess && !vaultUnlocked(accountId) ->
            "Your vault is locked. Unlock it with your PIN or fingerprint to continue."

        else -> null
    }

    /**
     * Document contents need a recent PIN/biometric unlock, enforced here as well as in the
     * UI. "PIN every time" (timeout 0) still allows a short window to finish the action.
     */
    private suspend fun vaultUnlocked(accountId: Int?): Boolean {
        if (accountId == null) return false
        val profile = runCatching { vault.execute(NegotiatorPass, accountId, GetProfile) }.getOrNull()
        val minutes = (profile?.pinExpiryTime ?: DEFAULT_PIN_MINUTES).coerceAtLeast(MIN_UNLOCK_MINUTES)
        return vaultLock.isUnlocked(minutes)
    }

    private fun hasRecoveryGrant(email: String): Boolean {
        val grant = recoveryGrant ?: return false
        return grant.email.equals(email.trim(), ignoreCase = true) &&
            System.currentTimeMillis() < grant.expiresAt
    }

    private suspend fun afterSuccess(op: VaultOp<*>, result: Any?) {
        when (op) {
            is Authenticate, is CreateAccount -> {
                vaultLock.lock()
                session.setAccountId((result as User).id)
            }
            is VerifyPinForReset -> recoveryGrant = RecoveryGrant(
                email = op.email.trim(),
                expiresAt = System.currentTimeMillis() + RECOVERY_WINDOW_MS,
            )
            is ResetPassword -> recoveryGrant = null
            DeleteAccount -> signOut()
            else -> Unit
        }
    }

    // ---- audit ----

    private enum class Decision { Allowed, Denied, Failed }

    private suspend fun audit(
        accountId: Int?,
        requester: Requester,
        purpose: Purpose,
        operation: String,
        decision: Decision,
        reason: String?,
    ) {
        // The audit log must never break the request it describes.
        runCatching {
            vault.audit(
                NegotiatorPass,
                AuditEntryEntity(
                    at = System.currentTimeMillis(),
                    // Account rows can be deleted; keep the log entry but drop the link.
                    accountId = accountId.takeUnless { operation == DeleteAccount.name },
                    requester = requester.name,
                    purpose = purpose.name,
                    operation = operation,
                    decision = decision.name,
                    reason = reason,
                ),
            )
        }
    }

    private companion object {
        const val RECOVERY_WINDOW_MS = 10 * 60_000L
        const val DEFAULT_PIN_MINUTES = 5
        const val MIN_UNLOCK_MINUTES = 2
    }
}
