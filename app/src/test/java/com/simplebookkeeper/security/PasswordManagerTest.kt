package com.simplebookkeeper.security

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PasswordManagerTest {

    @Test
    fun testGenerateSalt_shouldCreate16ByteArray() {
        val salt = PasswordManager.generateSalt()
        assertEquals(16, salt.size)
    }

    @Test
    fun testGenerateSalt_shouldCreateUniqueValues() {
        val salt1 = PasswordManager.generateSalt()
        val salt2 = PasswordManager.generateSalt()
        assertFalse(salt1.contentEquals(salt2))
    }

    @Test
    fun testDeriveKey_shouldProduce256BitKey() {
        val password = "testPassword123"
        val salt = ByteArray(16) { it.toByte() }
        val key = PasswordManager.deriveKey(password, salt)
        assertEquals("AES", key.algorithm)
        assertEquals(32, key.encoded.size) // 256 bits = 32 bytes
    }

    @Test
    fun testDeriveKey_sameInputsShouldProduceSameKey() {
        val password = "testPassword123"
        val salt = ByteArray(16) { 1 }
        val key1 = PasswordManager.deriveKey(password, salt)
        val key2 = PasswordManager.deriveKey(password, salt)
        assertArrayEquals(key1.encoded, key2.encoded)
    }

    @Test
    fun testDeriveKey_differentSaltsShouldProduceDifferentKeys() {
        val password = "testPassword123"
        val salt1 = ByteArray(16) { 1 }
        val salt2 = ByteArray(16) { 2 }
        val key1 = PasswordManager.deriveKey(password, salt1)
        val key2 = PasswordManager.deriveKey(password, salt2)
        assertFalse(key1.encoded.contentEquals(key2.encoded))
    }

    @Test
    fun testEncryptDecryptData_shouldRestoreOriginalData() {
        val password = "testPassword123"
        val salt = ByteArray(16) { it.toByte() }
        val key = PasswordManager.deriveKey(password, salt)
        val originalData = "Hello, this is a test message!".toByteArray()

        val encrypted = PasswordManager.encryptData(originalData, key)
        val decrypted = PasswordManager.decryptData(encrypted, key)

        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun testEncryptData_shouldProduceDifferentOutputEachTime() {
        val password = "testPassword123"
        val salt = ByteArray(16) { it.toByte() }
        val key = PasswordManager.deriveKey(password, salt)
        val originalData = "Test data".toByteArray()

        val encrypted1 = PasswordManager.encryptData(originalData, key)
        val encrypted2 = PasswordManager.encryptData(originalData, key)

        // Due to random IV, same plaintext should produce different ciphertext
        assertFalse(encrypted1.contentEquals(encrypted2))

        // But both should decrypt to same plaintext
        assertArrayEquals(originalData, PasswordManager.decryptData(encrypted1, key))
        assertArrayEquals(originalData, PasswordManager.decryptData(encrypted2, key))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDecryptData_tooShortInput_shouldThrow() {
        val password = "testPassword123"
        val salt = ByteArray(16) { it.toByte() }
        val key = PasswordManager.deriveKey(password, salt)
        val tooShortData = ByteArray(5) { it.toByte() }

        PasswordManager.decryptData(tooShortData, key)
    }
}
