package cn.stbu.helper.backend.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only persistent credential store. Keystore failure never falls back to a software key. */
class CredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "stbuhelper.credentials.v1"

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return@synchronized it.secretKey }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized fun save(username: String, password: String) {
        require(username.isNotBlank() && username.length <= 128 && password.isNotEmpty() && password.length <= 1024)
        saveJson("school", JSONObject().put("username", username).put("password", password))
    }

    @Synchronized fun saveJson(bucket: String, value: JSONObject) {
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encoded = JSONObject().put("iv", encode(cipher.iv)).put("ciphertext", encode(cipher.doFinal(plain)))
            check(preferences.edit().putString(bucket, encoded.toString()).commit()) { "无法写入手机存储" }
        } finally { plain.fill(0) }
    }

    @Synchronized fun read(): JSONObject? {
        return readJson("school")
    }

    @Synchronized fun readJson(bucket: String): JSONObject? {
        val blob = preferences.getString(bucket, null) ?: return null
        val value = JSONObject(blob)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(value.getString("iv"))))
        }
        val plain = cipher.doFinal(decode(value.getString("ciphertext")))
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }

    fun status(): JSONObject = try {
        val credentials = read()
        JSONObject().put("saved", credentials != null).put("broken", false)
            .put("username", credentials?.optString("username")?.let { if (it.length > 5) it.take(3) + "****" + it.takeLast(2) else "已保存" } ?: "")
    } catch (_: Exception) { JSONObject().put("saved", false).put("broken", true).put("username", "") }

    @Synchronized fun clear() {
        remove("school")
    }

    @Synchronized fun remove(bucket: String) { check(preferences.edit().remove(bucket).commit()) { "未能删除凭据，请重试" } }

    private fun encode(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object { private val keyLock = Any() }
}
