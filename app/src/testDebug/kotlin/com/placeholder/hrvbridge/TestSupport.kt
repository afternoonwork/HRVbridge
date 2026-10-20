package com.placeholder.hrvbridge

import android.app.Application
import android.content.Context
import android.os.Looper
import com.placeholder.hrvbridge.core.Intervals
import com.placeholder.hrvbridge.core.SessionStore
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.Duration
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Robolectric has no AndroidKeyStore; a plain JCE key stands in for it. */
class InMemoryKeyProvider : KeyProvider {
    private var key: SecretKey? = null

    override fun getOrCreateKey(): SecretKey =
        key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun deleteKey() {
        key = null
    }
}

fun testCredentialStore(app: Application, keys: KeyProvider = InMemoryKeyProvider()) =
    CredentialStore(app.getSharedPreferences(CredentialStore.PREFS, Context.MODE_PRIVATE), keys)

fun testController(
    app: Application,
    sessionsDir: java.io.File = Files.createTempDirectory("sessions").toFile(),
    credentials: CredentialStore = testCredentialStore(app),
    baseUrl: String = Intervals.BASE_URL,
) = MeasurementController(app, credentials, SessionStore(sessionsDir), baseUrl = baseUrl)

/** Runs the main looper until [done] or the (real-time) timeout, for work finishing on other threads. */
fun idleUntil(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!done() && System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        Thread.sleep(10)
    }
}
