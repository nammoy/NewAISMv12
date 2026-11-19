package com.app.aisheildme.namtran.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted document storage inside the app's private files directory.
 *
 * Envelope encryption: each file gets its own random AES-256 key; the file is sealed with
 * AES-GCM under that key, and the key itself is sealed with a non-exportable,
 * hardware-backed Android Keystore key. Blob layout:
 *
 *   "AV1" | iv(12) | wrappedKey(32+16 tag) | iv(12) | ciphertext+tag
 */
internal class VaultFileStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "vault_blobs").apply { mkdirs() }
    private val random = SecureRandom()

    /** Encrypts [plain] into a new blob and returns its path relative to the vault root. */
    fun write(accountId: Int, plain: ByteArray): String {
        val dataKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val wrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
            val wrapped = Cipher.getInstance(GCM).run {
                init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, wrapIv))
                doFinal(dataKey)
            }
            val dataIv = ByteArray(IV_BYTES).also(random::nextBytes)
            val body = Cipher.getInstance(GCM).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, dataIv))
                doFinal(plain)
            }

            val relative = "$accountId/${UUID.randomUUID()}.bin"
            val target = File(root, relative).apply { parentFile?.mkdirs() }
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { out ->
                out.write(MAGIC)
                out.write(wrapIv)
                out.write(wrapped)
                out.write(dataIv)
                out.write(body)
            }
            check(temp.renameTo(target)) { "Could not store the document." }
            return relative
        } finally {
            dataKey.fill(0)
        }
    }

    fun read(relative: String): ByteArray {
        val blob = resolve(relative).readBytes()
        require(blob.size > HEADER_BYTES && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Unrecognised vault file."
        }
        var at = MAGIC.size
        val wrapIv = blob.copyOfRange(at, at + IV_BYTES).also { at += IV_BYTES }
        val wrapped = blob.copyOfRange(at, at + WRAPPED_BYTES).also { at += WRAPPED_BYTES }
        val dataIv = blob.copyOfRange(at, at + IV_BYTES).also { at += IV_BYTES }

        val dataKey = Cipher.getInstance(GCM).run {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, wrapIv))
            doFinal(wrapped)
        }
        try {
            return Cipher.getInstance(GCM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, dataIv))
                doFinal(blob, at, blob.size - at)
            }
        } finally {
            dataKey.fill(0)
        }
    }

    fun delete(relative: String) {
        runCatching { resolve(relative).delete() }
    }

    fun deleteAccount(accountId: Int) {
        File(root, accountId.toString()).deleteRecursively()
    }

    /** Refuses paths that would escape the vault directory. */
    private fun resolve(relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Invalid vault path." }
        return file
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(false) // we supply a fresh random IV per wrap
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aishield_vault_files"
        const val GCM = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val WRAPPED_BYTES = KEY_BYTES + TAG_BITS / 8
        val HEADER_BYTES = MAGIC.size + IV_BYTES + WRAPPED_BYTES + IV_BYTES
    }
}
