package com.cloudbox.app.core.data.local.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份码加解密：用户密码 → PBKDF2 派生密钥 → AES-GCM。
 *
 * ⚠️ 与蓝云原版的**有意偏离**（安全）：原版用硬编码固定密钥
 * `"LanyunByStardew6"`（func.lua:887/1890），意味着任何人拿到备份码都能解开，
 * 等于没加密。这里改为用户密码派生 + 每条随机 salt：
 * - KDF：PBKDF2WithHmacSHA256，100000 次迭代，256-bit
 * - 加密：AES/GCM/NoPadding，随机 12 字节 IV，128-bit tag，认证加密（防篡改）
 * - 输出：`CBOX1:` + base64(salt[16] | iv[12] | ciphertext+tag)
 *
 * **密码不落盘**：只用于本次加解密；忘记密码则备份码不可恢复（与原版语义一致）。
 */
object BackupCrypto {

    private const val PREFIX = "CBOX1:"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 是否为加密备份码（用于恢复时判断要不要问密码） */
    fun isEncryptedToken(text: String): Boolean = text.trim().startsWith(PREFIX)

    fun encrypt(plain: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
    }

    /** @throws IllegalArgumentException 密码错误 / 数据被篡改（GCM tag 校验失败） */
    fun decrypt(token: String, password: String): String {
        val body = token.trim().removePrefix(PREFIX)
        val packed = Base64.decode(body, Base64.NO_WRAP)
        require(packed.size > SALT_BYTES + IV_BYTES) { "备份码内容不完整" }
        val salt = packed.copyOfRange(0, SALT_BYTES)
        val iv = packed.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val ct = packed.copyOfRange(SALT_BYTES + IV_BYTES, packed.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("密码错误，或备份码已损坏")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
