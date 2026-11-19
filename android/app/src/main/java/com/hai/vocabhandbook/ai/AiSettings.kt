package com.hai.vocabhandbook.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Lưu API key đã mã hoá bằng khoá trong Android Keystore (giống Keychain bên iOS) */
object SecureStore {
    private const val ALIAS = "vocab_gemini_key"
    private const val PREFS = "secure_store"
    private const val FIELD = "gemini_api_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun saveApiKey(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(FIELD).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString(FIELD, stored).apply()
    }

    fun readApiKey(context: Context): String? = try {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIELD, null)
        if (stored == null) null else {
            val (ivB64, encB64) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null   // khoá không giải được (vd. khôi phục sang máy khác) thì coi như chưa có key
    }
}

/** Cấu hình AI không bí mật + thống kê đã dùng */
object AiSettings {
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    /** Model Google đã ngừng, gặp thì dùng model mặc định mới */
    private val retiredModels = setOf("gemini-3.5-flash", "gemini-3.7-flash", "gemini-2.5-flash", "gemini-2.0-flash")

    private fun prefs(context: Context) = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun model(context: Context): String {
        val m = (prefs(context).getString("model", "") ?: "").trim()
        return if (m.isEmpty() || m in retiredModels) DEFAULT_MODEL else m
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString("model", model.trim()).apply()
    }

    /** Người dùng đã đồng ý cho gửi ảnh tới Google Gemini chưa */
    fun consent(context: Context): Boolean = prefs(context).getBoolean("consent", false)

    fun setConsent(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("consent", value).apply()
    }

    data class Stats(val requests: Int, val inputTokens: Long, val outputTokens: Long)

    fun stats(context: Context): Stats {
        val p = prefs(context)
        return Stats(p.getInt("requests", 0), p.getLong("input_tokens", 0), p.getLong("output_tokens", 0))
    }

    fun recordUsage(context: Context, input: Int, output: Int) {
        val s = stats(context)
        prefs(context).edit()
            .putInt("requests", s.requests + 1)
            .putLong("input_tokens", s.inputTokens + input)
            .putLong("output_tokens", s.outputTokens + output)
            .apply()
    }
}
