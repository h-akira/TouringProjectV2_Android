package com.touringproject.v2.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
  fun encrypt(plain: ByteArray): ByteArray
  fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that never leaves the Android Keystore. Output is the 12-byte IV followed by the ciphertext. */
class KeystoreCipher(private val alias: String = "trv2.apiKey") : SecretCipher {
  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build(),
    )
    return generator.generateKey()
  }

  override fun encrypt(plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    return cipher.iv + cipher.doFinal(plain)
  }

  override fun decrypt(sealed: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
    return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
  }

  private companion object {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
  }
}

const val API_KEY_KEY = "trv2.apiKey"

/** The connection key, encrypted at rest. A value that cannot be decrypted (e.g. the Keystore key is gone) reads as no key. */
class ApiKeyStore(private val store: KeyValueStore, private val cipher: SecretCipher) {
  private val state = MutableStateFlow<String?>(null)
  val apiKey: StateFlow<String?> = state.asStateFlow()

  suspend fun load(): String? {
    val sealed = store.get(API_KEY_KEY)
    state.value = sealed?.let {
      runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrNull()
    }
    return state.value
  }

  fun current(): String? = state.value

  /** Throws when the key cannot be sealed or stored; the key in use then stays the old one. */
  suspend fun save(key: String?) {
    store.set(API_KEY_KEY, key?.let { Base64.getEncoder().encodeToString(cipher.encrypt(it.toByteArray(Charsets.UTF_8))) })
    state.value = key
  }
}
