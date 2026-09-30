package com.vault.secretcamera.security

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    // Derive AES-256 SecretKey from PIN/Password and Salt using PBKDF2
    fun deriveKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    // Generate random cryptographic salt
    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    // Encrypt an InputStream to an encrypted File
    fun encryptStreamToFile(input: InputStream, outputFile: File, secretKey: SecretKey) {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        FileOutputStream(outputFile).use { fos ->
            // Prepend IV to the file
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    cos.write(buffer, 0, bytesRead)
                }
                cos.flush()
            }
        }
    }

    // Decrypt an encrypted File to an OutputStream
    fun decryptFileToStream(encryptedFile: File, output: OutputStream, secretKey: SecretKey) {
        FileInputStream(encryptedFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = fis.read(iv)
            if (ivRead != GCM_IV_LENGTH) {
                throw IllegalStateException("ملف مشفر تالف أو غير صالح")
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            CipherInputStream(fis, cipher).use { cis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (cis.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        }
    }

    // Decrypt directly into memory bytes (for in-app photo viewing without saving unencrypted file to disk!)
    fun decryptFileToBytes(encryptedFile: File, secretKey: SecretKey): ByteArray {
        FileInputStream(encryptedFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = fis.read(iv)
            if (ivRead != GCM_IV_LENGTH) {
                throw IllegalStateException("ملف مشفر تالف")
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            CipherInputStream(fis, cipher).use { cis ->
                return cis.readBytes()
            }
        }
    }
}
