package com.placeholder.hrvbridge

import android.content.Context
import com.placeholder.hrvbridge.core.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CredentialStoreTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences(CredentialStore.PREFS, Context.MODE_PRIVATE)

    @Test
    fun roundTripsAndPersistsAcrossInstances() {
        val keys = InMemoryKeyProvider()
        testCredentialStore(app, keys).save(Credential.ApiKey("abc123"))
        assertEquals(Credential.ApiKey("abc123"), testCredentialStore(app, keys).load())
        val oauth = Credential.OAuth("tok", "i1", "Paul")
        testCredentialStore(app, keys).save(oauth)
        assertEquals(oauth, testCredentialStore(app, keys).load())
    }

    @Test
    fun storesOnlyCiphertext() {
        testCredentialStore(app).save(Credential.ApiKey("abc123"))
        val raw = prefs.all.values.joinToString()
        assertFalse(raw, raw.contains("abc123"))
        assertFalse(raw, raw.contains("apiKey"))
    }

    @Test
    fun unreadableCiphertextIsClearedNotThrown() {
        testCredentialStore(app).save(Credential.ApiKey("abc123"))
        // A different key, e.g. after a restore onto a new device.
        assertNull(testCredentialStore(app, InMemoryKeyProvider()).load())
        assertEquals(emptyMap<String, Any>(), prefs.all)
        prefs.edit().putString("credential", "not base64 !!").commit()
        assertNull(testCredentialStore(app).load())
    }

    @Test
    fun keystoreFailureMeansNotConnected() {
        val keys = InMemoryKeyProvider()
        testCredentialStore(app, keys).save(Credential.ApiKey("abc123"))
        val broken = object : KeyProvider {
            override fun getOrCreateKey() = throw java.security.UnrecoverableKeyException("gone")

            override fun deleteKey() = Unit
        }
        assertNull(testCredentialStore(app, broken).load())
    }

    private fun throwingKeys(e: RuntimeException) = object : KeyProvider {
        override fun getOrCreateKey() = throw e

        override fun deleteKey() = Unit
    }

    @Test
    fun providerExceptionOnLoadMeansNotConnected() {
        testCredentialStore(app).save(Credential.ApiKey("abc123"))
        // Some devices throw this RuntimeException from AndroidKeyStore.
        assertNull(testCredentialStore(app, throwingKeys(java.security.ProviderException("keystore"))).load())
    }

    @Test
    fun saveReportsFailureInsteadOfThrowing() {
        val store = testCredentialStore(app, throwingKeys(java.security.ProviderException("keystore")))
        assertFalse(store.save(Credential.ApiKey("abc123")))
        assertEquals(emptyMap<String, Any>(), prefs.all)
        val invalid = object : KeyProvider {
            override fun getOrCreateKey(): javax.crypto.SecretKey = javax.crypto.spec.SecretKeySpec(ByteArray(5), "AES")

            override fun deleteKey() = Unit
        }
        assertFalse(testCredentialStore(app, invalid).save(Credential.ApiKey("abc123")))
        assert(testCredentialStore(app).save(Credential.ApiKey("abc123")))
    }

    @Test
    fun clearRemovesCredential() {
        val keys = InMemoryKeyProvider()
        val store = testCredentialStore(app, keys)
        store.save(Credential.ApiKey("abc123"))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun prefsFileMatchesBackupExclusions() {
        val rules = app.resources.openRawResource(R.xml.data_extraction_rules).bufferedReader().readText()
        assertEquals("auth", CredentialStore.PREFS)
        // Compiled XML is binary; the exclusion path still appears as a string.
        assert(rules.contains("auth.xml")) { "auth.xml not excluded in data_extraction_rules" }
    }
}
