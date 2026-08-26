package com.cloudbox.app.core.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cloudbox.app.core.domain.model.AccountInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号密码与 Cookie 的安全存储（EncryptedSharedPreferences）。
 *
 * 为什么用 EncryptedSharedPreferences 而不是普通 SharedPreferences/DataStore：
 * phpdisk_info 是完整登录凭证，泄露 = 账号被盗；DataStore 明文落盘不安全。
 * EncryptedSharedPreferences 基于 Android Keystore 的 AES256-GCM 主密钥加密，
 * 密钥不落盘，设备级保护（security-crypto 1.1.0-alpha06）。
 *
 * 多账号设计：所有 key 以 uid 为前缀形成独立槽位：
 *   pwd_<uid>      —— 密码（可选保存）
 *   cookies_<uid>  —— Set-Cookie 文本（多行，含 phpdisk_info / ylogin）
 *   active_at_<uid> —— 最近活跃时间戳
 */
@Singleton
class AccountSecureStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_SECURE_ACCOUNTS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---------- 账号槽位 ----------

    /** 全部已保存的 uid（按保存顺序） */
    fun allUids(): List<String> =
        prefs.getString(KEY_UID_LIST, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun saveUid(uid: String) {
        val list = allUids().toMutableList()
        if (uid !in list) {
            list.add(uid)
            prefs.edit().putString(KEY_UID_LIST, list.joinToString("\n")).apply()
        }
    }

    fun removeUid(uid: String) {
        val list = allUids().filter { it != uid }
        val editor = prefs.edit()
            .putString(KEY_UID_LIST, list.joinToString("\n"))
            .remove("$PREFIX_PWD$uid")
            .remove("$PREFIX_COOKIES$uid")
            .remove("$PREFIX_ACTIVE$uid")
        // #8 修复：只有删除的是【当前账号】时才清 currentUid；
        // 旧实现无条件 remove，导致删除非当前账号 B 时把正在使用的账号 A 也登出
        if (currentUid() == uid) editor.remove(KEY_CURRENT_UID)
        editor.apply()
    }

    // ---------- 当前账号 ----------

    fun currentUid(): String? = prefs.getString(KEY_CURRENT_UID, null)

    fun setCurrentUid(uid: String) = prefs.edit().putString(KEY_CURRENT_UID, uid).apply()

    fun clearCurrentUid() = prefs.edit().remove(KEY_CURRENT_UID).apply()

    // ---------- 密码 ----------

    fun savePassword(uid: String, pwd: String) =
        prefs.edit().putString("$PREFIX_PWD$uid", pwd).apply()

    fun loadPassword(uid: String): String? = prefs.getString("$PREFIX_PWD$uid", null)

    fun removePassword(uid: String) = prefs.edit().remove("$PREFIX_PWD$uid").apply()

    // ---------- Cookie ----------

    /** 保存账号的全部 Cookie（Set-Cookie 文本，每行一条） */
    fun saveCookies(uid: String, cookieLines: List<String>) {
        prefs.edit().putString("$PREFIX_COOKIES$uid", cookieLines.joinToString("\n")).apply()
        touchActive(uid)
    }

    fun loadCookieLines(uid: String): List<String> =
        prefs.getString("$PREFIX_COOKIES$uid", "")?.split("\n")?.filter { it.isNotBlank() }
            ?: emptyList()

    fun clearCookies(uid: String) = prefs.edit().remove("$PREFIX_COOKIES$uid").apply()

    // ---------- 活跃时间 ----------

    fun touchActive(uid: String) =
        prefs.edit().putLong("$PREFIX_ACTIVE$uid", System.currentTimeMillis()).apply()

    fun lastActiveAt(uid: String): Long = prefs.getLong("$PREFIX_ACTIVE$uid", 0L)

    // ---------- 账号信息 ----------

    fun accountInfo(uid: String): AccountInfo = AccountInfo(
        uid = uid,
        lastActiveAt = lastActiveAt(uid)
    )

    companion object {
        private const val FILE_SECURE_ACCOUNTS = "secure_accounts"
        private const val KEY_UID_LIST = "uid_list"
        private const val KEY_CURRENT_UID = "current_uid"
        private const val PREFIX_PWD = "pwd_"
        private const val PREFIX_COOKIES = "cookies_"
        private const val PREFIX_ACTIVE = "active_at_"
    }
}
