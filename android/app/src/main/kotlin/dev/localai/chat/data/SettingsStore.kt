package dev.localai.chat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val systemPrompt: String = "",
    /** null = let the model/server decide */
    val temperature: Float? = null,
    val lastModel: String? = null,
    val lastWorkflow: String? = null,
)

/**
 * Settings in SharedPreferences. The API key is encrypted with an AES key that lives in the
 * Android Keystore (never leaves secure hardware where available).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        prefs.edit()
            .putString("serverUrl", next.serverUrl)
            .putString("apiKeyEnc", if (next.apiKey.isEmpty()) "" else KeyCipher.encrypt(next.apiKey))
            .putString("systemPrompt", next.systemPrompt)
            .putFloat("temperature", next.temperature ?: -1f)
            .putString("lastModel", next.lastModel)
            .putString("lastWorkflow", next.lastWorkflow)
            .apply()
        _state.value = next
    }

    private fun read(): AppSettings {
        val enc = prefs.getString("apiKeyEnc", "").orEmpty()
        val temp = prefs.getFloat("temperature", -1f)
        return AppSettings(
            serverUrl = prefs.getString("serverUrl", "").orEmpty(),
            apiKey = if (enc.isEmpty()) "" else KeyCipher.decrypt(enc).orEmpty(),
            systemPrompt = prefs.getString("systemPrompt", "").orEmpty(),
            temperature = if (temp < 0f) null else temp,
            lastModel = prefs.getString("lastModel", null),
            lastWorkflow = prefs.getString("lastWorkflow", null),
        )
    }
}

private object KeyCipher {
    private const val ALIAS = "local_ai_gateway_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns null if the data can't be decrypted (e.g. the keystore was reset). */
    fun decrypt(enc: String): String? = runCatching {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
