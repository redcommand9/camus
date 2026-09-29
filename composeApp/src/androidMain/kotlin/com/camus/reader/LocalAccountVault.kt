package com.camus.reader

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local reading-data storage for this device.
 *
 * Device encryption is optional and off by default: a fresh install keeps library, bookmark, and
 * note data in plain [SharedPreferences]. A person can turn encryption on from Settings, which asks
 * for a profile password and moves the current data behind an AES-GCM vault keyed from that
 * password (via PBKDF2). Turning it back off decrypts and returns to plain storage.
 *
 * Either way, [readData] is served from an in-memory cache instead of decrypting on every call, and
 * [writeData] only updates that cache immediately; the (possibly encrypted) write to disk is
 * coalesced onto a background dispatcher a short moment later so frequent saves - a page turn, a
 * scroll position, a highlight - don't each pay for a full re-encrypt of the whole vault on the
 * calling thread. Call [flushPending] before the process might die (e.g. onStop) to guarantee the
 * latest state actually reaches disk.
 */
internal class LocalAccountVault(private val preferences: SharedPreferences) {
    private var unlockedKey: ByteArray? = null
    private var cache: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingFlush: Job? = null

    fun hasProfile(): Boolean = preferences.contains(KEY_PROFILE_NAME) && preferences.contains(KEY_SALT)
    fun profileName(): String = preferences.getString(KEY_PROFILE_NAME, "") ?: ""

    /** Whether reading data is currently kept behind a password-derived vault. */
    fun encryptionEnabled(): Boolean = hasProfile()

    /** True once data can be read/written: always true in plain mode, only after [unlock] in encrypted mode. */
    val isReady: Boolean get() = !hasProfile() || unlockedKey != null

    @Synchronized
    fun createProfile(name: String, password: CharArray) {
        require(name.trim().isNotEmpty()) { "Enter a profile name." }
        require(password.size >= 8) { "Use a password with at least 8 characters." }
        check(!hasProfile()) { "A local profile already exists." }

        val seed = cache ?: readPlainData()
        val salt = ByteArray(PasswordVaultCrypto.SALT_BYTES).also(random::nextBytes)
        val iterations = PasswordVaultCrypto.calibratedIterations()
        val key = PasswordVaultCrypto.deriveKey(password, salt, iterations)
        val encrypted = PasswordVaultCrypto.encrypt(seed.toByteArray(Charsets.UTF_8), key)
        val editor = preferences.edit()
            .putString(KEY_PROFILE_NAME, name.trim())
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_CIPHERTEXT, encode(encrypted))
            .putInt(KEY_ITERATIONS, iterations)
            .remove(KEY_PLAINTEXT_DATA)
            .remove("themeDark")
            .remove("ptgEnabled")
            .remove("lastBook")
            .remove("library")
        preferences.all.keys.filter { it.startsWith("book:") }.forEach { editor.remove(it) }
        check(editor.commit()) { "The encrypted profile could not be saved." }
        pendingFlush?.cancel()
        unlockedKey?.fill(0)
        unlockedKey = key
        cache = seed
    }

    @Synchronized
    fun unlock(password: CharArray): Boolean {
        val salt = decode(preferences.getString(KEY_SALT, null)) ?: return false
        val ciphertext = decode(preferences.getString(KEY_CIPHERTEXT, null)) ?: return false
        val iterations = preferences.getInt(KEY_ITERATIONS, PasswordVaultCrypto.DEFAULT_ITERATIONS)
            .coerceIn(100_000, 1_000_000)
        val candidate = PasswordVaultCrypto.deriveKey(password, salt, iterations)
        val plaintext = try { PasswordVaultCrypto.decrypt(ciphertext, candidate) } catch (_: Exception) { null }
        if (plaintext != null) {
            unlockedKey?.fill(0)
            unlockedKey = candidate
            cache = plaintext.decodeToString()
            if (iterations == PasswordVaultCrypto.DEFAULT_ITERATIONS) runCatching {
                val adjusted = PasswordVaultCrypto.calibratedIterations()
                if (adjusted < iterations) {
                    val nextSalt = ByteArray(PasswordVaultCrypto.SALT_BYTES).also(random::nextBytes)
                    val nextKey = PasswordVaultCrypto.deriveKey(password, nextSalt, adjusted)
                    var migrated = false
                    try {
                        val nextCiphertext = PasswordVaultCrypto.encrypt(plaintext, nextKey)
                        if (preferences.edit().putString(KEY_SALT, encode(nextSalt))
                                .putString(KEY_CIPHERTEXT, encode(nextCiphertext))
                                .putInt(KEY_ITERATIONS, adjusted).commit()) {
                            candidate.fill(0)
                            unlockedKey = nextKey
                            migrated = true
                        }
                    } finally { if (!migrated) nextKey.fill(0) }
                }
            }
        } else {
            candidate.fill(0)
        }
        plaintext?.fill(0)
        return plaintext != null
    }

    @Synchronized
    fun lock() {
        flushPending()
        unlockedKey?.fill(0)
        unlockedKey = null
        cache = null
    }

    /** Decrypts the vault and moves its contents into plain storage. Call only while unlocked. */
    @Synchronized
    fun disableEncryption() {
        val key = unlockedKey ?: error("Unlock your local profile first.")
        flushPending()
        val current = cache ?: PasswordVaultCrypto.decrypt(
            decode(preferences.getString(KEY_CIPHERTEXT, null)) ?: error("The local vault is missing."), key,
        ).decodeToString()
        check(
            preferences.edit()
                .putString(KEY_PLAINTEXT_DATA, current)
                .remove(KEY_PROFILE_NAME)
                .remove(KEY_SALT)
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_ITERATIONS)
                .commit(),
        ) { "Could not switch off device encryption." }
        unlockedKey?.fill(0)
        unlockedKey = null
        cache = current
    }

    @Synchronized
    fun readData(): String {
        cache?.let { return it }
        val loaded = if (hasProfile()) {
            val key = unlockedKey ?: error("Unlock your local profile first.")
            val ciphertext = decode(preferences.getString(KEY_CIPHERTEXT, null)) ?: error("The local vault is missing.")
            PasswordVaultCrypto.decrypt(ciphertext, key).decodeToString()
        } else readPlainData()
        cache = loaded
        return loaded
    }

    @Synchronized
    fun writeData(value: String) {
        cache = value
        scheduleFlush()
    }

    /** Forces any debounced write to actually reach disk now. Call before the process may be killed. */
    @Synchronized
    fun flushPending() {
        pendingFlush?.cancel()
        pendingFlush = null
        val value = cache ?: return
        persist(value)
    }

    private fun scheduleFlush() {
        pendingFlush?.cancel()
        val value = cache ?: return
        pendingFlush = scope.launch {
            delay(FLUSH_DELAY_MS)
            persist(value)
        }
    }

    /** Does the actual (possibly encrypted) write. Safe to call from a background dispatcher. */
    private fun persist(value: String) {
        if (hasProfile()) {
            val key = unlockedKey ?: return
            val encrypted = PasswordVaultCrypto.encrypt(value.toByteArray(Charsets.UTF_8), key)
            preferences.edit().putString(KEY_CIPHERTEXT, encode(encrypted)).apply()
        } else {
            preferences.edit().putString(KEY_PLAINTEXT_DATA, value).apply()
        }
    }

    private fun readPlainData(): String {
        preferences.getString(KEY_PLAINTEXT_DATA, null)?.let { return it }
        return readLegacyData()
    }

    /** Pre-vault installs kept these loose in SharedPreferences; folded in the first time they're read. */
    private fun readLegacyData(): String {
        val root = JSONObject()
        preferences.all.forEach { (key, value) ->
            if (key == "themeDark" || key == "ptgEnabled" || key == "lastBook" || key == "library" || key.startsWith("book:")) {
                root.put(key, value)
            }
        }
        return root.toString()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String?): ByteArray? = value?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    companion object {
        private const val KEY_PROFILE_NAME = "localProfile.name"
        private const val KEY_SALT = "localProfile.salt"
        private const val KEY_CIPHERTEXT = "localProfile.vault"
        private const val KEY_ITERATIONS = "localProfile.kdfIterations"
        private const val KEY_PLAINTEXT_DATA = "localProfile.plainData"
        private const val FLUSH_DELAY_MS = 400L
        private val random = SecureRandom()
    }
}

internal object PasswordVaultCrypto {
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** Target a brief unlock on the actual device; older profiles keep their stored cost until unlocked. */
    fun calibratedIterations(): Int {
        val probePassword = "camus-reader-calibration".toCharArray()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val start = SystemClock.elapsedRealtimeNanos()
        deriveKey(probePassword, salt, 50_000).fill(0)
        probePassword.fill('\u0000')
        val elapsed = ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L).coerceAtLeast(1L)
        return ((50_000L * 350L / elapsed).coerceIn(100_000L, DEFAULT_ITERATIONS.toLong()) / 10_000L * 10_000L).toInt()
    }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    fun decrypt(payload: ByteArray, key: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "The local vault is damaged." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size))
    }
}
