package com.app.aisheildme.namtran.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretHasherTest {

    private val iterations = 1_000 // keep the test fast; production uses far more

    @Test
    fun `correct secret matches`() {
        val salt = SecretHasher.newSalt()
        val hash = SecretHasher.hash("correct horse", salt, iterations)
        assertTrue(SecretHasher.matches("correct horse", salt, iterations, hash))
    }

    @Test
    fun `wrong secret does not match`() {
        val salt = SecretHasher.newSalt()
        val hash = SecretHasher.hash("correct horse", salt, iterations)
        assertFalse(SecretHasher.matches("correct hors", salt, iterations, hash))
    }

    @Test
    fun `same secret with different salts gives different hashes`() {
        val a = SecretHasher.hash("1234", SecretHasher.newSalt(), iterations)
        val b = SecretHasher.hash("1234", SecretHasher.newSalt(), iterations)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `hash is 256 bits`() {
        assertEquals(32, SecretHasher.hash("x", SecretHasher.newSalt(), iterations).size)
    }
}
