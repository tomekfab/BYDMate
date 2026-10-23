package com.bydmate.app.voice.openclaw

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64

/**
 * Plan step 6: the real [OpenClawIdentityStore] on a device, with the real Android Keystore.
 * JVM mocks cannot prove sign, encrypt, decrypt and clear against the Keystore, so this does.
 * Uses a test-only key alias and directory; production data is never touched.
 */
@RunWith(AndroidJUnit4::class)
class OpenClawIdentityStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val alias = "bydmate_openclaw_device_test"

    private fun store() = OpenClawIdentityStore(dir, KeystoreSecretCipher(alias))

    private fun aliasPresent() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun ByteArray.has(needle: ByteArray) =
        needle.isNotEmpty() && (0..size - needle.size).any { s -> needle.indices.all { this[s + it] == needle[it] } }

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "openclaw-device-test")
        store().clear()
        dir.deleteRecursively()
        Log.i(TAG, "device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} abi=${Build.SUPPORTED_ABIS.first()}")
    }

    @After
    fun tearDown() {
        store().clear()
        dir.deleteRecursively()
    }

    @Test
    fun keystoreKeyIsCreatedOnFirstUseAndNeverExported() {
        assertFalse(aliasPresent())
        store().getOrCreateIdentity()
        assertTrue("the Keystore holds the wrapping key", aliasPresent())
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        // A Keystore secret key never exposes its bytes to the app process.
        assertNull(key.encoded)
    }

    @Test
    fun identitySignsTheKnownAnswerAfterBeingEncryptedAndReloaded() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        store().getOrCreateIdentity(seed)

        val reloaded = store().identity()!! // a new instance: decrypts from disk through the Keystore
        assertEquals("21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9", reloaded.deviceId)
        val payload = OpenClawProtocol.signingPayload(reloaded.deviceId, "", "NONCE-123", 1737264000000L)
        assertEquals(
            "F7NS7xmfmeBSmE0XowFvnul_U33ilE7Z5CRsS-7yWpeuYvcXC9ur0Ypkfdq9szV04FjhL2WyfalyCbmSfQpOBw",
            reloaded.sign(payload),
        )
        val signature = Base64.getUrlDecoder().decode(reloaded.sign(payload))
        assertTrue(verify(reloaded.publicKey, payload.toByteArray(), signature))
        assertFalse(verify(reloaded.publicKey, (payload + "x").toByteArray(), signature))
    }

    @Test
    fun tokenRoundTripsAndNothingReadableIsOnDisk() {
        val identity = store().getOrCreateIdentity()
        store().saveDeviceToken("tok-device-PLAINTEXT-77")
        assertEquals("tok-device-PLAINTEXT-77", store().deviceToken())

        val files = dir.listFiles().orEmpty().filter { it.isFile }
        assertEquals(listOf(OpenClawIdentityStore.FILE_NAME), files.map { it.name })
        val bytes = files.single().readBytes()
        assertFalse(bytes.has("tok-device-PLAINTEXT-77".toByteArray()))
        assertFalse(bytes.has(identity.seed))
        assertFalse(bytes.has(identity.deviceId.toByteArray()))
        assertFalse(bytes.has(Base64.getEncoder().encode(identity.seed)))
        assertTrue("stored under noBackupFilesDir", dir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test
    fun encryptionIsRandomizedAndTamperingIsDetected() {
        val cipher = KeystoreSecretCipher(alias)
        val plain = "same plaintext".toByteArray()
        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)
        assertFalse("a fresh IV every time", first.contentEquals(second))
        assertArrayEquals(plain, cipher.decrypt(first))
        val tampered = first.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            cipher.decrypt(tampered)
            fail("a tampered blob must not decrypt")
        } catch (_: Exception) {
            // expected: GCM tag mismatch
        }
    }

    @Test
    fun clearDeletesTheFileAndTheKeystoreKey() {
        val old = store().getOrCreateIdentity()
        store().saveDeviceToken("tok")
        assertTrue(aliasPresent())

        store().clear()

        assertFalse("the Keystore key is gone", aliasPresent())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertNull(store().identity())
        assertNull(store().deviceToken())
        assertNotEquals(old.deviceId, store().getOrCreateIdentity().deviceId)
    }

    @Test
    fun aDeletedKeystoreKeyMakesTheStoredIdentityUnreadable() {
        val old = store().getOrCreateIdentity()
        store().saveDeviceToken("tok")
        // The key disappears, as after a device restore, while the file stays.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        val reopened = store()
        assertNull(reopened.identity())
        assertNull(reopened.deviceToken())
        assertTrue("the unreadable file is removed", dir.listFiles().orEmpty().isEmpty())
        assertNotEquals(old.deviceId, reopened.getOrCreateIdentity().deviceId)
    }

    private fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(data, 0, data.size)
        }.verifySignature(signature)

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val TAG = "OpenClawIdentityDevice"
    }
}
