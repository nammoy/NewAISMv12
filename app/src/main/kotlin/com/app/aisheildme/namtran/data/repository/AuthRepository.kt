package com.app.aisheildme.namtran.data.repository

import android.content.Context
import android.net.Uri
import com.app.aisheildme.namtran.core.common.AppException
import com.app.aisheildme.namtran.data.local.SessionStore
import com.app.aisheildme.namtran.data.local.VaultLock
import com.app.aisheildme.namtran.domain.model.DriverLicense
import com.app.aisheildme.namtran.domain.model.EmergencyContact
import com.app.aisheildme.namtran.domain.model.FamilyDoctor
import com.app.aisheildme.namtran.domain.model.InsuranceInfo
import com.app.aisheildme.namtran.domain.model.PatientMedicalDetail
import com.app.aisheildme.namtran.domain.model.User
import com.app.aisheildme.namtran.negotiator.Negotiator
import com.app.aisheildme.namtran.negotiator.Purpose
import com.app.aisheildme.namtran.negotiator.Requester
import com.app.aisheildme.namtran.vault.AccountHasPin
import com.app.aisheildme.namtran.vault.Authenticate
import com.app.aisheildme.namtran.vault.ChangePassword
import com.app.aisheildme.namtran.vault.ChangePin
import com.app.aisheildme.namtran.vault.CreateAccount
import com.app.aisheildme.namtran.vault.DeleteAccount
import com.app.aisheildme.namtran.vault.GetProfile
import com.app.aisheildme.namtran.vault.ObserveProfile
import com.app.aisheildme.namtran.vault.ResetPassword
import com.app.aisheildme.namtran.vault.SaveProfile
import com.app.aisheildme.namtran.vault.SetPin
import com.app.aisheildme.namtran.vault.VerifyPin
import com.app.aisheildme.namtran.vault.VerifyPinForReset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, offline accounts. Credentials are checked inside the vault (only salted hashes are
 * stored); every call goes through the [Negotiator].
 */
@Singleton
class AuthRepository @Inject constructor(
    private val negotiator: Negotiator,
    private val sessionStore: SessionStore,
    private val vaultLock: VaultLock,
    @param:ApplicationContext private val context: Context,
) {

    val user: Flow<User?> = negotiator.observe(Requester.Profile, Purpose.OwnerView, ObserveProfile)

    @Volatile
    private var legacyPurged = false

    suspend fun currentUser(): User? {
        if (!legacyPurged) {
            sessionStore.purgeLegacyData()
            legacyPurged = true
        }
        if (sessionStore.currentAccountId() == null) return null
        return try {
            negotiator.request(Requester.Profile, Purpose.OwnerView, GetProfile)
        } catch (e: AppException) {
            null
        }
    }

    suspend fun login(email: String, password: String): User =
        negotiator.request(Requester.Auth, Purpose.Authenticate, Authenticate(email, password))

    /** Creates the account on this device and signs in. */
    suspend fun register(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        confirmPassword: String,
        profile: RegisterProfile? = null,
    ): String {
        if (password != confirmPassword) throw AppException("Passwords do not match")
        val draft = User(
            firstname = firstName.trim(),
            lastname = lastName.trim(),
            email = email.trim(),
            dob = profile?.dob,
            gender = profile?.gender,
            mobileNo = profile?.mobileNo,
            address = profile?.address,
            city = profile?.city,
            state = profile?.state,
            zipcode = profile?.zipcode,
        )
        negotiator.request(Requester.Auth, Purpose.Authenticate, CreateAccount(email, password, draft))
        return "Your account was created on this device."
    }

    /** Whether this email can recover its password with the vault PIN. */
    suspend fun canRecoverWithPin(email: String): Boolean =
        negotiator.request(Requester.Auth, Purpose.Authenticate, AccountHasPin(email))

    /**
     * Step 1 of offline password recovery. Returns the token for step 2
     * ([setNewPassword]); the Negotiator only honours it for the next 10 minutes.
     */
    suspend fun verifyPinForReset(email: String, pin: String): String {
        negotiator.request(Requester.Auth, Purpose.Authenticate, VerifyPinForReset(email, pin))
        return email.trim()
    }

    suspend fun setNewPassword(
        token: String,
        newPassword: String,
        confirmPassword: String,
    ): String {
        if (newPassword != confirmPassword) throw AppException("Passwords do not match")
        negotiator.request(Requester.Auth, Purpose.Authenticate, ResetPassword(token, newPassword))
        return "Your password was reset. Please sign in."
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
    ): String {
        if (newPassword != confirmPassword) throw AppException("Passwords do not match")
        negotiator.request(Requester.Auth, Purpose.OwnerEdit, ChangePassword(currentPassword, newPassword))
        negotiator.signOut()
        return "Password changed. Please sign in again."
    }

    suspend fun setPin(pin: String, confirmPin: String): String {
        if (pin != confirmPin) throw AppException("PINs do not match")
        negotiator.request(Requester.Auth, Purpose.OwnerEdit, SetPin(pin))
        return "Vault PIN saved."
    }

    suspend fun resetPin(oldPin: String, pin: String, confirmPin: String): String {
        if (pin != confirmPin) throw AppException("PINs do not match")
        negotiator.request(Requester.Auth, Purpose.OwnerEdit, ChangePin(oldPin, pin))
        return "Vault PIN changed."
    }

    suspend fun verifyPin(pin: String): String {
        negotiator.request(Requester.Auth, Purpose.OwnerView, VerifyPin(pin))
        vaultLock.markUnlocked()
        return "Vault unlocked."
    }

    fun isVaultUnlocked(pinExpiryMinutes: Int): Boolean = vaultLock.isUnlocked(pinExpiryMinutes)

    fun markVaultUnlocked() = vaultLock.markUnlocked()

    /** Kept for callers that used to re-fetch from the server; the vault is the source of truth. */
    suspend fun refreshProfile(): User = currentUser() ?: throw AppException("Please sign in first.")

    suspend fun updateProfile(
        firstName: String? = null,
        lastName: String? = null,
        imageUrl: String? = null,
        pinExpiryTime: Int? = null,
        isEnableFaceId: Boolean? = null,
        isEnableBiometric: Boolean? = null,
        pushNotification: Boolean? = null,
    ): User = editProfile { user ->
        user.copy(
            firstname = firstName?.trim() ?: user.firstname,
            lastname = lastName?.trim() ?: user.lastname,
            name = null,
            imageUrl = imageUrl ?: user.imageUrl,
            pinExpiryTime = pinExpiryTime ?: user.pinExpiryTime,
            isEnableFaceId = isEnableFaceId ?: user.isEnableFaceId,
            isEnableBiometric = isEnableBiometric ?: user.isEnableBiometric,
            pushNotification = pushNotification ?: user.pushNotification,
        )
    }

    suspend fun saveProfileSetup(
        emergencyContact: EmergencyContact? = null,
        insurance: InsuranceInfo? = null,
        familyDoctor: FamilyDoctor? = null,
        driverLicense: DriverLicense? = null,
        medicalDetail: PatientMedicalDetail? = null,
        bloodGroup: String? = null,
    ): User? {
        val nothingToSave = listOfNotNull(
            emergencyContact?.takeUnless { it.isEmpty },
            insurance?.takeUnless { it.isEmpty },
            familyDoctor?.takeUnless { it.isEmpty },
            driverLicense?.takeUnless { it.isEmpty },
            medicalDetail?.takeUnless { it.isEmpty },
            bloodGroup?.takeIf { it.isNotBlank() },
        ).isEmpty()
        if (nothingToSave) return null

        return editProfile { user ->
            user.copy(
                emergencyContacts = emergencyContact?.takeUnless { it.isEmpty }
                    ?.let { listOf(it) + user.emergencyContacts.drop(1) }
                    ?: user.emergencyContacts,
                insurance = insurance?.takeUnless { it.isEmpty } ?: user.insurance,
                familyDoctor = familyDoctor?.takeUnless { it.isEmpty } ?: user.familyDoctor,
                driverLicense = driverLicense?.takeUnless { it.isEmpty } ?: user.driverLicense,
                patientMedicalDetail = medicalDetail?.takeUnless { it.isEmpty } ?: user.patientMedicalDetail,
                bloodGroup = bloodGroup?.trim()?.takeIf { it.isNotEmpty() } ?: user.bloodGroup,
            )
        }
    }

    suspend fun logout(callApi: Boolean = true) = negotiator.signOut()

    /** Permanently removes this account and everything it owns from the device. */
    suspend fun deleteAccount() {
        val accountId = sessionStore.currentAccountId()
        negotiator.request(Requester.Profile, Purpose.OwnerEdit, DeleteAccount)
        accountId?.let { avatarDir(it).deleteRecursively() }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) = sessionStore.setBiometricEnabled(enabled)

    /** Copies the chosen photo into app-private storage and returns its local URI. */
    suspend fun uploadAvatar(uri: Uri, fileName: String): String = withContext(Dispatchers.IO) {
        val accountId = sessionStore.currentAccountId() ?: throw AppException("Please sign in first.")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AppException("Could not read the selected image.")
        if (bytes.size > MAX_AVATAR_BYTES) throw AppException("That image is too large (max 10 MB).")

        val dir = avatarDir(accountId).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val extension = fileName.substringAfterLast('.', "jpg").take(5)
        val file = File(dir, "avatar_${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        Uri.fromFile(file).toString()
    }

    suspend fun rememberedEmail(): String? = sessionStore.rememberedEmail()

    suspend fun setRememberedEmail(email: String?) = sessionStore.setRememberedEmail(email)

    private suspend fun editProfile(transform: (User) -> User): User {
        val current = negotiator.request(Requester.Profile, Purpose.OwnerView, GetProfile)
            ?: throw AppException("Please sign in first.")
        return negotiator.request(Requester.Profile, Purpose.OwnerEdit, SaveProfile(transform(current)))
    }

    private fun avatarDir(accountId: Int) = File(context.filesDir, "avatars/$accountId")

    private companion object {
        const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
    }
}

data class RegisterProfile(
    val dob: String,
    val gender: String,
    val mobileNo: String,
    val address: String,
    val city: String,
    val state: String,
    val zipcode: String,
)
