/**
 * ZONA-OSIER — AES Encryptor untuk GitHub-as-Cloud.
 * 
 * Mengenkripsi data memori di sisi klien SEBELUM di-commit/push ke Git.
 * GitHub hanya menyimpan ciphertext.
 * 
 * Menggunakan AndroidX Security Crypto (AES-GCM) yang 
 * menyimpan key di KeyStore per-app (tidak bisa diekstrak).
 */
package com.zonaosier.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AESEncryptor(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setKeyAlias(ALIAS)
            .build()
    }

    /**
     * Enkripsi bytes plaintext.
     * @return Ciphertext bytes.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        // Prepend IV ke ciphertext
        return iv + encrypted
    }

    /**
     * Dekripsi bytes ciphertext.
     * @return Plaintext bytes.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val ivSize = GCM_IV_LENGTH
        val iv = ciphertext.copyOfRange(0, ivSize)
        val encrypted = ciphertext.copyOfRange(ivSize, ciphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        return cipher.doFinal(encrypted)
    }

    /**
     * Enkripsi string ke string (base64).
     */
    fun encryptToString(plaintext: String): String {
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /**
     * Dekripsi string dari base64.
     */
    fun decryptFromString(ciphertext: String): String {
        val bytes = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
        return String(decrypt(bytes), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "zona_osier_memory_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
    }
}
