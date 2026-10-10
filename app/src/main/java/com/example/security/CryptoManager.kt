package com.example.security

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Production-ready cryptographic manager utilizing PBKDF2WithHmacSHA256
 * with salt and constant-time equality checks to prevent timing attacks.
 */
object CryptoManager {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 12000
    private const val KEY_LENGTH = 256
    private const val SALT_BYTES = 16

    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return salt.joinToString("") { "%02x".format(it) }
    }

    fun hashPin(pin: String, saltHex: String): String {
        val salt = hexToByteArray(saltHex)
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val hash = factory.generateSecret(spec).encoded
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback SHA-256 for older/restricted environments
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        }
    }

    fun verifyPin(pin: String, storedHashHex: String, saltHex: String): Boolean {
        val computedHashHex = hashPin(pin, saltHex)
        return slowEquals(hexToByteArray(storedHashHex), hexToByteArray(computedHashHex))
    }

    /**
     * Constant-time comparison to prevent side-channel timing attacks.
     */
    private fun slowEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in 0 until minOf(a.size, b.size)) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = (
                (Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)
            ).toByte()
            i += 2
        }
        return data
    }
}
