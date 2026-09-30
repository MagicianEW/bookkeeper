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

    // ─── hex 编解码回归测试（v0.4.6 修复）─────────────────────────────
    //
    // 旧实现用 BigInteger(hex, 16).toByteArray() 还原 salt，会丢弃前导零字节。
    // 随机 salt 首字节恰为 0x00 的概率约 1/256，此时盐被截短 → PBKDF2 密钥不同
    // → 正确密码也无法通过校验 → 用户被永久锁在应用外。以下用例锁死该行为。

    @Test
    fun testHexRoundTrip_shouldPreserveLeadingZeroBytes() {
        // 首字节为 0x00 的 16 字节盐（旧实现在这里会返回 15 字节）
        val salt = byteArrayOf(0x00, 0x41, 0xc9.toByte(), 0xa2.toByte(), 0xb7.toByte(),
            0xe5.toByte(), 0xd0.toByte(), 0x14, 0x8a.toByte(), 0xc6.toByte(), 0xb2.toByte(),
            0xf9.toByte(), 0xe7.toByte(), 0xd3.toByte(), 0x10, 0x7a)

        val hex = PasswordManager.bytesToHex(salt)
        assertEquals(32, hex.length)
        assertEquals("0041c9a2b7e5d0148ac6b2f9e7d3107a", hex)

        val restored = PasswordManager.hexToBytes(hex)
        assertEquals(16, restored.size)
        assertArrayEquals(salt, restored)
    }

    @Test
    fun testHexRoundTrip_shouldPreserveHighBitAndMultipleLeadingZeros() {
        // 首字节最高位为 1（旧 BigInteger 实现会补 0x00 再被裁掉，需保证仍还原正确）
        val highBit = byteArrayOf(0x9f.toByte(), 0x3a, 0x9c.toByte(), 0x22, 0xb7.toByte(),
            0xe5.toByte(), 0xd0.toByte(), 0x14, 0x8a.toByte(), 0xc6.toByte(), 0xb2.toByte(),
            0xf9.toByte(), 0xe7.toByte(), 0xd3.toByte(), 0x10, 0x75)
        assertArrayEquals(highBit, PasswordManager.hexToBytes(PasswordManager.bytesToHex(highBit)))

        // 连续多个前导零字节
        val manyZeros = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val restored = PasswordManager.hexToBytes(PasswordManager.bytesToHex(manyZeros))
        assertEquals(16, restored.size)
        assertArrayEquals(manyZeros, restored)
    }

    @Test
    fun testHexRoundTrip_shouldHoldForManyRandomSalts() {
        // 用生成盐做 5000 次往返，覆盖 1/256 的首字节零值情形
        repeat(5000) {
            val salt = PasswordManager.generateSalt()
            val restored = PasswordManager.hexToBytes(PasswordManager.bytesToHex(salt))
            assertEquals("salt 长度必须保持 ${salt.size}", salt.size, restored.size)
            assertArrayEquals(salt, restored)
        }
    }

    @Test
    fun testHashPasswordPbkdf2_shouldVerifyWithLeadingZeroSalt() {
        // 端到端复现原 bug：首字节为 0 的盐，设置后必须能用同一密码验证通过
        val salt = byteArrayOf(0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            0x77, 0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte())
        val password = "1234"

        val (saltHex, hashHex) = PasswordManager.hashPasswordPbkdf2(password, salt)
        assertEquals("0000112233445566778899aabbccddee", saltHex)

        // 模拟 verifyPassword 的路径：从存储值还原盐后重新计算
        val (recomputedSaltHex, recomputedHash) =
            PasswordManager.hashPasswordPbkdf2(password, PasswordManager.hexToBytes(saltHex))
        assertEquals(saltHex, recomputedSaltHex)
        assertArrayEquals(hashHex.toByteArray(), recomputedHash.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testHexToBytes_oddLength_shouldThrow() {
        PasswordManager.hexToBytes("abc")
    }
}
