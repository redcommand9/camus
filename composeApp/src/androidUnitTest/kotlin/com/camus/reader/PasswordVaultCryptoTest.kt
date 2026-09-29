package com.camus.reader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PasswordVaultCryptoTest {
    @Test
    fun passwordKeyEncryptsAndRestoresVaultData() {
        val salt = ByteArray(PasswordVaultCrypto.SALT_BYTES) { it.toByte() }
        val password = "camus-reader-test".toCharArray()
        val key = PasswordVaultCrypto.deriveKey(password, salt)
        val data = "library metadata and notes".toByteArray()
        val encrypted = PasswordVaultCrypto.encrypt(data, key)

        assertContentEquals(data, PasswordVaultCrypto.decrypt(encrypted, key))
        password.fill('\u0000')
        key.fill(0)
    }

    @Test
    fun aDifferentPasswordCannotUnlockTheVault() {
        val salt = ByteArray(PasswordVaultCrypto.SALT_BYTES) { (it + 1).toByte() }
        val key = PasswordVaultCrypto.deriveKey("correct horse".toCharArray(), salt)
        val wrongKey = PasswordVaultCrypto.deriveKey("incorrect horse".toCharArray(), salt)
        val encrypted = PasswordVaultCrypto.encrypt("private".toByteArray(), key)

        assertFailsWith<Exception> { PasswordVaultCrypto.decrypt(encrypted, wrongKey) }
        key.fill(0)
        wrongKey.fill(0)
    }

    @Test
    fun changingCiphertextIsDetected() {
        val salt = ByteArray(PasswordVaultCrypto.SALT_BYTES) { (it * 2).toByte() }
        val key = PasswordVaultCrypto.deriveKey("long enough password".toCharArray(), salt)
        val encrypted = PasswordVaultCrypto.encrypt("private".toByteArray(), key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertFailsWith<Exception> { PasswordVaultCrypto.decrypt(encrypted, key) }
        key.fill(0)
    }

    @Test
    fun passwordKeysAreSalted() {
        val password = "same password".toCharArray()
        val first = PasswordVaultCrypto.deriveKey(password, ByteArray(PasswordVaultCrypto.SALT_BYTES))
        val second = PasswordVaultCrypto.deriveKey(password, ByteArray(PasswordVaultCrypto.SALT_BYTES) { 1 })

        assertNotEquals(first.toList(), second.toList())
        password.fill('\u0000')
        first.fill(0)
        second.fill(0)
    }
}
