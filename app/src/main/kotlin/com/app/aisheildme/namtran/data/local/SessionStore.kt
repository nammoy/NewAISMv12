package com.app.aisheildme.namtran.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aishieldme_session")

/**
 * Small, non-sensitive session state. Profile and health data live only in the encrypted
 * vault; this store keeps just *which* local account is signed in plus UI preferences.
 */
@Singleton
class SessionStore @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val ACCOUNT_ID = intPreferencesKey("account_id")
        val REMEMBERED_EMAIL = stringPreferencesKey("remembered_email")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")

        // Written by the old server-based build: a plaintext profile copy and an API token.
        val LEGACY_USER = stringPreferencesKey("user")
        val LEGACY_API_TOKEN = stringPreferencesKey("api_token")
    }

    private val preferences: Flow<Preferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    val accountId: Flow<Int?> = preferences.map { it[Keys.ACCOUNT_ID] }.distinctUntilChanged()

    suspend fun currentAccountId(): Int? = accountId.first()

    suspend fun setAccountId(id: Int?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACCOUNT_ID) else prefs[Keys.ACCOUNT_ID] = id
        }
    }

    /** Removes the plaintext profile and token the old server-based build left behind. */
    suspend fun purgeLegacyData() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LEGACY_USER)
            prefs.remove(Keys.LEGACY_API_TOKEN)
        }
    }

    suspend fun rememberedEmail(): String? =
        preferences.first()[Keys.REMEMBERED_EMAIL]?.takeIf { it.isNotEmpty() }

    suspend fun setRememberedEmail(email: String?) {
        context.dataStore.edit { prefs ->
            if (email.isNullOrEmpty()) prefs.remove(Keys.REMEMBERED_EMAIL)
            else prefs[Keys.REMEMBERED_EMAIL] = email
        }
    }

    suspend fun isBiometricEnabled(): Boolean =
        preferences.first()[Keys.BIOMETRIC_ENABLED] ?: false

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    // ---- Temporary: used by the remaining server calls until they move into the vault. ----

    suspend fun apiToken(): String? = null

    suspend fun clear() = purgeLegacyData()
}
