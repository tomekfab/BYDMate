package com.bydmate.app.voice.openclaw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the identity file. The key never leaves the implementation. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
    fun deleteKey()
}

/**
 * AES-256-GCM with a key held by the Android Keystore. The Keystore on Android 10 has no native
 * Ed25519, so the signing seed is encrypted by this key and signed in software (step 2 evidence).
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Output: one IV-length byte, the IV, then the ciphertext with its GCM tag. */
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val secret = key(create = false) ?: throw GeneralSecurityException("Keystore key is missing")
        require(blob.isNotEmpty()) { "empty blob" }
        val ivLength = blob[0].toInt() and 0xff
        require(blob.size > 1 + ivLength) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, blob, 1, ivLength)) }
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
    }

    override fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "bydmate_openclaw_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/** Ed25519 device identity. The Gateway names the device by the SHA-256 of the raw public key. */
class OpenClawIdentity(internal val seed: ByteArray) {
    init {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
    }

    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    val publicKey: ByteArray = privateKey.generatePublicKey().encoded
    val deviceId: String = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }

    fun signBytes(data: ByteArray): ByteArray =
        Ed25519Signer().apply { init(true, privateKey); update(data, 0, data.size) }.generateSignature()

    /** Signature over the UTF-8 payload, base64url without padding. */
    fun sign(payload: String): String = OpenClawProtocol.base64Url(signBytes(payload.toByteArray(Charsets.UTF_8)))

    override fun toString() = "OpenClawIdentity(deviceId=$deviceId)"
}

/**
 * Holds the device identity and the revocable device token in one file under `noBackupFilesDir`,
 * encrypted by [cipher]. Neither value is ever logged, backed up or exposed in a `toString`.
 * An unreadable file (key lost, tampering, corruption) is deleted: the device must then pair again.
 */
class OpenClawIdentityStore(private val dir: File, private val cipher: SecretCipher) {

    private class Stored(val seed: ByteArray, val token: String?)

    private val file get() = File(dir, FILE_NAME)

    @Synchronized
    fun identity(): OpenClawIdentity? = load()?.let { OpenClawIdentity(it.seed) }

    /** The stored identity, or a new one. A [seed] is used only when a new identity is created. */
    @Synchronized
    fun getOrCreateIdentity(seed: ByteArray? = null): OpenClawIdentity {
        load()?.let { return OpenClawIdentity(it.seed) }
        val fresh = seed ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val identity = OpenClawIdentity(fresh)
        write(Stored(fresh, null))
        return identity
    }

    @Synchronized
    fun deviceToken(): String? = load()?.token

    @Synchronized
    fun isPaired(): Boolean = deviceToken() != null

    @Synchronized
    fun saveDeviceToken(token: String) {
        val stored = checkNotNull(load()) { "no device identity to attach a token to" }
        write(Stored(stored.seed, token))
    }

    @Synchronized
    fun clearDeviceToken() {
        load()?.let { write(Stored(it.seed, null)) }
    }

    /** Deletes the file and the Keystore key. The next identity is a different device. */
    @Synchronized
    fun clear() {
        file.delete()
        File(dir, TEMP_NAME).delete()
        cipher.deleteKey()
    }

    private fun load(): Stored? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(String(cipher.decrypt(file.readBytes()), Charsets.UTF_8))
            val seed = Base64.getUrlDecoder().decode(json.getString("seed"))
            require(seed.size == 32)
            Stored(seed, json.optString("token").takeIf { it.isNotEmpty() })
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(stored: Stored) {
        dir.mkdirs()
        val json = JSONObject().put("v", 1).put("seed", OpenClawProtocol.base64Url(stored.seed))
        stored.token?.let { json.put("token", it) }
        val temp = File(dir, TEMP_NAME)
        temp.writeBytes(cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("could not store the OpenClaw identity")
        }
    }

    companion object {
        const val DIRECTORY_NAME = "openclaw"
        const val FILE_NAME = "identity.bin"
        private const val TEMP_NAME = "identity.bin.tmp"

        fun create(context: Context, cipher: SecretCipher = KeystoreSecretCipher()) =
            OpenClawIdentityStore(File(context.noBackupFilesDir, DIRECTORY_NAME), cipher)
    }
}
