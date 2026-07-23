package ch.lkmc.blipbird.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BYO API keys, AES-GCM-encrypted with an AndroidKeyStore key (PLAN.md §4.1).
 * There is no first-party encrypted DataStore and androidx.security-crypto is
 * deprecated, so this is a custom [Serializer] with a versioned envelope and a
 * fresh random nonce per write. The file is excluded from Auto Backup (the
 * Keystore key is device-bound); if decryption ever fails the store resets and
 * the app re-prompts for keys instead of crashing.
 */
@Serializable
data class ProviderKeys(
    val aeroDataBoxKey: String? = null,
    val aeroApiKey: String? = null,
    // Optional OpenSky API client (OAuth2 client credentials) — only unlocks the
    // exact-flown-path backfill; every other feature works without it.
    val openSkyClientId: String? = null,
    val openSkyClientSecret: String? = null,
)

@Singleton
class ProviderKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store: DataStore<ProviderKeys> = DataStoreFactory.create(
        serializer = EncryptedKeysSerializer,
        produceFile = { context.dataStoreFile("provider_keys.pb") },
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ProviderKeys() },
    )

    val keys: Flow<ProviderKeys> = store.data.catch { emit(ProviderKeys()) }
    val hasAnyStatusKey: Flow<Boolean> = keys.map { it.aeroDataBoxKey != null || it.aeroApiKey != null }
    val hasOpenSkyClient: Flow<Boolean> = keys.map { it.openSkyClientId != null && it.openSkyClientSecret != null }

    suspend fun setAeroDataBoxKey(key: String?) = store.updateData { it.copy(aeroDataBoxKey = key?.trim()?.ifEmpty { null }) }
    suspend fun setAeroApiKey(key: String?) = store.updateData { it.copy(aeroApiKey = key?.trim()?.ifEmpty { null }) }
    suspend fun setOpenSkyClientId(value: String?) = store.updateData { it.copy(openSkyClientId = value?.trim()?.ifEmpty { null }) }
    suspend fun setOpenSkyClientSecret(value: String?) = store.updateData { it.copy(openSkyClientSecret = value?.trim()?.ifEmpty { null }) }

    private object EncryptedKeysSerializer : Serializer<ProviderKeys> {
        private const val KEY_ALIAS = "blipbird_provider_keys"
        private const val ENVELOPE_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        override val defaultValue: ProviderKeys = ProviderKeys()

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return gen.generateKey()
        }

        override suspend fun readFrom(input: InputStream): ProviderKeys {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return defaultValue
            try {
                require(bytes.size > 1 + 12 && bytes[0].toInt() == ENVELOPE_VERSION)
                val iv = bytes.copyOfRange(1, 13)
                val ciphertext = bytes.copyOfRange(13, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(ciphertext)
                return json.decodeFromString(ProviderKeys.serializer(), plain.decodeToString())
            } catch (t: Throwable) {
                // Device-bound key invalidated or file corrupted → clean reset, re-prompt.
                throw CorruptionException("provider key store unreadable", t)
            }
        }

        override suspend fun writeTo(t: ProviderKeys, output: OutputStream) {
            val plain = json.encodeToString(ProviderKeys.serializer(), t).encodeToByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv                      // fresh random nonce per write
            val ciphertext = cipher.doFinal(plain)
            output.write(ENVELOPE_VERSION)
            output.write(iv)
            output.write(ciphertext)
        }
    }
}
