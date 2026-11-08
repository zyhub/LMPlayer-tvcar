package com.lm.player.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 服务器凭据的 Android Keystore AES-GCM 加解密（移植自手机端，旧明文自动兼容）。
 *
 * TV 端目前只用于加密「会话过期自动重登」所需的服务器密码（servers.saltOrSecret）；
 * tokenOrApiKey 列仍为明文，迁移见审计报告 S-09。
 */
object ServerTokenCipher {
    private const val PREFIX = "enc:v1:"
    private const val ALIAS = "lm_server_token_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrElse { error ->
            // 部分电视盒子 Keystore 实现残缺：加密失败时保留明文，避免凭据被写成空值导致无法重登。
            Log.e("ServerTokenCipher", "encrypt failed; storing plaintext", error)
            plain
        }
    }

    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        if (!value.startsWith(PREFIX)) return value // 旧库明文
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(":", limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
