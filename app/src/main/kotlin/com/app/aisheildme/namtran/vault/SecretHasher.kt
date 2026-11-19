package com.app.aisheildme.namtran.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 hashing for passwords and PINs. The plain secret never
 * leaves the vault and is never stored.
 */
internal object SecretHasher {

    const val PASSWORD_ITERATIONS = 120_000
    const val PIN_ITERATIONS = 60_000

    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun hash(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = secret.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    /** Constant-time comparison so timing doesn't reveal how much of a guess was right. */
    fun matches(secret: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(secret, salt, iterations), expected)
}
