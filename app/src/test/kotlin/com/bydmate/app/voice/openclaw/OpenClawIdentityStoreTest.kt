package com.bydmate.app.voice.openclaw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Software AES-GCM stand-in for the Android Keystore cipher. The real Keystore behaviour is proven
 * on a device by OpenClawIdentityStoreDeviceTest; this fake only covers the store's own logic.
 */
internal class FakeSecretCipher : SecretCipher {
    private var key: ByteArray? = null
    var deleteKeyCalls = 0
        private set
    val keyExists get() = key != null

    private fun key(): SecretKeySpec {
        val k = key ?: ByteArray(32).also { SecureRandom().nextBytes(it); key = it }
        return SecretKeySpec(k, "AES")
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val k = key ?: throw AEADBadTagException("key was deleted")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, blob, 0, 12)) }
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    override fun deleteKey() {
        key = null
        deleteKeyCalls++
    }

    /** Simulates a Keystore key that was invalidated (restored device, new lock screen). */
    fun invalidate() {
        key = null
    }
}

class OpenClawIdentityStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = FakeSecretCipher()
    private fun dir() = File(tmp.root, "openclaw")
    private fun store() = OpenClawIdentityStore(dir(), cipher)

    private fun allBytes(): List<ByteArray> = dir().listFiles().orEmpty().filter { it.isFile }.map { it.readBytes() }

    private fun ByteArray.contains(needle: ByteArray) =
        needle.isNotEmpty() && (0..size - needle.size).any { s -> needle.indices.all { this[s + it] == needle[it] } }

    @Test
    fun `a fresh store has no identity and no token`() {
        val store = store()
        assertNull(store.identity())
        assertNull(store.deviceToken())
        assertFalse(store.isPaired())
    }

    @Test
    fun `identity is created once and survives a new store instance`() {
        val first = store().getOrCreateIdentity()
        val second = store().getOrCreateIdentity()
        assertEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.publicKey, second.publicKey)
        assertEquals(32, first.publicKey.size)
        assertEquals(first.sign("x"), second.sign("x"))
    }

    @Test
    fun `an imported seed keeps its known answer`() {
        val seed = ByteArray(32) { 0 }.also {
            val hex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
            for (i in 0 until 32) it[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val identity = store().getOrCreateIdentity(seed)
        assertEquals("21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9", identity.deviceId)
    }

    @Test
    fun `an explicit seed does not replace an existing paired identity or token`() {
        val store = store()
        val existing = store.getOrCreateIdentity()
        store.saveDeviceToken("tok-existing")

        val returned = store.getOrCreateIdentity(ByteArray(32) { 7 })

        assertEquals(existing.deviceId, returned.deviceId)
        assertEquals(existing.deviceId, store.identity()!!.deviceId)
        assertEquals("tok-existing", store.deviceToken())
    }

    @Test
    fun `token is saved loaded replaced and cleared without losing the identity`() {
        val store = store()
        val identity = store.getOrCreateIdentity()
        store.saveDeviceToken("tok-one")
        assertEquals("tok-one", store().deviceToken())
        assertTrue(store().isPaired())

        store.saveDeviceToken("tok-two")
        assertEquals("tok-two", store().deviceToken())

        store.clearDeviceToken()
        assertNull(store().deviceToken())
        assertEquals(identity.deviceId, store().identity()!!.deviceId)
    }

    @Test
    fun `saving a token without an identity is refused`() {
        val store = store()
        assertTrue(runCatching { store.saveDeviceToken("tok") }.isFailure)
        assertNull(store.deviceToken())
    }

    @Test
    fun `nothing readable is written to disk`() {
        val store = store()
        val identity = store.getOrCreateIdentity()
        store.saveDeviceToken("tok-PLAINTEXT-123")
        val files = allBytes()
        assertTrue(files.isNotEmpty())
        val seedHex = identity.seed.joinToString("") { "%02x".format(it) }
        for (bytes in files) {
            assertFalse(bytes.contains("tok-PLAINTEXT-123".toByteArray()))
            assertFalse(bytes.contains(identity.seed))
            assertFalse(bytes.contains(seedHex.toByteArray()))
            assertFalse(bytes.contains(java.util.Base64.getEncoder().encode(identity.seed)))
            assertFalse(bytes.contains(java.util.Base64.getUrlEncoder().withoutPadding().encode(identity.seed)))
            assertFalse(bytes.contains(identity.deviceId.toByteArray()))
        }
    }

    @Test
    fun `clear deletes the files and the cipher key and the next identity is new`() {
        val store = store()
        val old = store.getOrCreateIdentity()
        store.saveDeviceToken("tok")

        store.clear()

        assertTrue(allBytes().isEmpty())
        assertEquals(1, cipher.deleteKeyCalls)
        assertNull(store.identity())
        assertNull(store.deviceToken())
        assertNotEquals(old.deviceId, store.getOrCreateIdentity().deviceId)
    }

    @Test
    fun `an undecryptable file is dropped and a new identity is created`() {
        val old = store().getOrCreateIdentity()
        store().saveDeviceToken("tok")
        cipher.invalidate()

        val reopened = store()
        assertNull(reopened.identity())
        assertNull(reopened.deviceToken())
        assertTrue("the unreadable file is removed", allBytes().isEmpty())
        assertNotEquals(old.deviceId, reopened.getOrCreateIdentity().deviceId)
    }

    @Test
    fun `a tampered file is dropped`() {
        store().getOrCreateIdentity()
        val file = dir().listFiles()!!.single { it.isFile }
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(store().identity())
        assertTrue(allBytes().isEmpty())
    }

    @Test
    fun `a corrupt json payload is dropped`() {
        store().getOrCreateIdentity()
        val file = dir().listFiles()!!.single { it.isFile }
        file.writeBytes(cipher.encrypt("not json".toByteArray()))

        assertNull(store().identity())
        assertNotNull(store().getOrCreateIdentity())
    }

    @Test
    fun `no temporary file is left behind after a write`() {
        val store = store()
        store.getOrCreateIdentity()
        store.saveDeviceToken("tok")
        assertEquals(listOf(OpenClawIdentityStore.FILE_NAME), dir().listFiles()!!.map { it.name })
    }
}
