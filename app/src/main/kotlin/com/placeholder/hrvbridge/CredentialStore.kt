package com.placeholder.hrvbridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.SecretBox
import com.placeholder.hrvbridge.core.SecretBoxException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface KeyProvider {
    fun getOrCreateKey(): SecretKey

    fun deleteKey()
}

/** AES-256-GCM key that never leaves the Android Keystore. */
class KeystoreKeyProvider(private val alias: String = "hrvbridge_credential") : KeyProvider {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun deleteKey() = keyStore().deleteEntry(alias)
}

/**
 * The Intervals.icu credential, encrypted with a Keystore key; the ciphertext
 * lives in SharedPreferences "auth", which is excluded from backup.
 */
class CredentialStore(private val prefs: SharedPreferences, private val keys: KeyProvider) {
    /** Null if nothing is stored or it can no longer be decrypted (then it is cleared). */
    fun load(): Credential? {
        val b64 = prefs.getString(KEY, null) ?: return null
        return try {
            val plain = SecretBox.open(keys.getOrCreateKey(), Base64.decode(b64, Base64.NO_WRAP))
            Credential.fromJson(plain.decodeToString())
        } catch (e: SecretBoxException) {
            clear()
            null
        } catch (e: GeneralSecurityException) {
            // Keystore trouble (e.g. unrecoverable key): reconnect rather than crash.
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        }
    }

    fun save(credential: Credential) {
        val box = SecretBox.seal(keys.getOrCreateKey(), credential.toJson().toByteArray())
        prefs.edit { putString(KEY, Base64.encodeToString(box, Base64.NO_WRAP)) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    companion object {
        /** Must match the backup exclusions in res/xml. */
        const val PREFS = "auth"
        private const val KEY = "credential"

        fun create(context: Context) =
            CredentialStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KeystoreKeyProvider())
    }
}
