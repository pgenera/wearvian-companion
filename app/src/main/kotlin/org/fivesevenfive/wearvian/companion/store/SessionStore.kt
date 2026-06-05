package org.fivesevenfive.wearvian.companion.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.fivesevenfive.wearvian.companion.auth.RivianAuthClient.SessionTokens

/**
 * Encrypted on-device storage for Rivian session tokens, so a periodic re-auth
 * can reuse them where possible. Holds NO key material for the vehicle — the
 * watch owns all BLE key material.
 */
class SessionStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wearvian_companion_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveTokens(tokens: SessionTokens) {
        prefs.edit()
            .putString(KEY_CSRF, tokens.csrfToken)
            .putString(KEY_ASESS, tokens.appSessionToken)
            .putString(KEY_USESS, tokens.userSessionToken)
            .apply()
    }

    fun loadTokens(): SessionTokens? {
        val csrf = prefs.getString(KEY_CSRF, null) ?: return null
        val aSess = prefs.getString(KEY_ASESS, null) ?: return null
        val uSess = prefs.getString(KEY_USESS, null) ?: return null
        return SessionTokens(csrf, aSess, uSess)
    }

    /** The last email signed in with, pre-filled on the next enrollment for convenience. */
    fun saveEmail(email: String) = prefs.edit().putString(KEY_EMAIL, email).apply()

    fun loadEmail(): String = prefs.getString(KEY_EMAIL, null).orEmpty()

    /** Clear tokens but keep the remembered email so the username stays pre-filled. */
    fun clear() {
        val email = prefs.getString(KEY_EMAIL, null)
        prefs.edit().clear().apply()
        if (email != null) prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    private companion object {
        const val KEY_CSRF = "csrfToken"
        const val KEY_ASESS = "appSessionToken"
        const val KEY_USESS = "userSessionToken"
        const val KEY_EMAIL = "lastEmail"
    }
}
