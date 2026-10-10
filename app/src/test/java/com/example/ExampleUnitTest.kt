package com.example

import com.example.security.CryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testCryptoManager_hashAndVerify() {
        val salt = CryptoManager.generateSalt()
        val pin = "4826"
        val hash = CryptoManager.hashPin(pin, salt)

        assertTrue(CryptoManager.verifyPin("4826", hash, salt))
        assertFalse(CryptoManager.verifyPin("1234", hash, salt))
        assertFalse(CryptoManager.verifyPin("4827", hash, salt))
    }

    @Test
    fun testFocusDurationCalculations() {
        val durationMinutes = 15
        val startTime = 1000000L
        val expirationTime = startTime + (durationMinutes * 60 * 1000L)
        val remainingMillis = expirationTime - (startTime + 5 * 60 * 1000L)

        val totalSeconds = (remainingMillis / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val formatted = "%02d:%02d".format(minutes, seconds)

        assertEquals("10:00", formatted)
    }
}
