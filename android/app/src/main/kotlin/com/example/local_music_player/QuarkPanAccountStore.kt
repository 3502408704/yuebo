package com.example.local_music_player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 夸克网盘账号（多账号模型）：会话即整串 Cookie（含 __puus 等），账号 id 在网盘内唯一。 */
internal data class QuarkPanAccount(
    val id: String,
    val user: QuarkPanUser,
    val cookie: String,
)

/**
 * 夸克网盘多账号本地持久化（SharedPreferences，随卸载清除）。
 * 同时维护“当前激活账号”，云端存储、播放与下载都使用激活账号；
 * 保留旧版单账号方法（saveCookie/loadUser 等），它们操作当前激活账号以便兼容既有调用。
 */
internal class QuarkPanAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("quark_pan_account", Context.MODE_PRIVATE)

    fun accounts(): List<QuarkPanAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null)
        if (raw.isNullOrBlank()) {
            // 迁移旧版单账号数据到多账号结构。
            val legacy = legacySingleAccount()
            if (legacy != null) {
                persist(listOf(legacy), legacy.id)
                return listOf(legacy)
            }
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        QuarkPanAccount(
                            id = obj.getString("id"),
                            user = QuarkPanUser(
                                name = obj.getString("name"),
                                avatarUrl = obj.optString("avatar_url", "").ifBlank { null },
                            ),
                            cookie = obj.getString("cookie"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE_ID, null)?.takeIf { it.isNotBlank() }

    fun activeAccount(): QuarkPanAccount? {
        val list = accounts()
        if (list.isEmpty()) return null
        val id = activeId()
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /** 新增或更新账号；若账号列表原本为空，则自动设为激活账号。 */
    fun saveAccount(account: QuarkPanAccount) {
        val current = accounts()
        val updated = if (current.any { it.id == account.id }) {
            current.map { if (it.id == account.id) account else it }
        } else {
            current + account
        }
        persist(updated, activeId() ?: account.id)
    }

    /** 登录成功后保存完整会话（Cookie + 用户信息）并切换为激活账号；同名账号视为重新登录覆盖。 */
    fun saveSession(cookie: String, user: QuarkPanUser) {
        val id = "quark:" + (user.name.ifBlank { "account" })
        saveAccount(QuarkPanAccount(id, user, cookie))
        setActive(id)
    }

    fun removeAccount(id: String) {
        val current = accounts().filterNot { it.id == id }
        persist(current, resolveActiveAfterRemove(activeId(), id, current.map { it.id }))
    }

    fun setActive(id: String) {
        if (accounts().any { it.id == id }) {
            prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        }
    }

    // ---- 旧版单账号兼容方法（操作当前激活账号）----

    fun saveCookie(cookie: String) {
        val existing = activeAccount()
        saveAccount(
            QuarkPanAccount(
                id = existing?.id ?: "quark-default",
                user = existing?.user ?: QuarkPanUser("", null),
                cookie = cookie,
            ),
        )
    }

    fun loadCookie(): String = activeAccount()?.cookie.orEmpty()

    fun loadUser(): QuarkPanUser? = activeAccount()?.user

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun persist(accounts: List<QuarkPanAccount>, activeId: String?) {
        val array = JSONArray()
        accounts.forEach { acc ->
            array.put(
                JSONObject().apply {
                    put("id", acc.id)
                    put("name", acc.user.name)
                    put("avatar_url", acc.user.avatarUrl ?: "")
                    put("cookie", acc.cookie)
                },
            )
        }
        prefs.edit()
            .putString(KEY_ACCOUNTS, array.toString())
            .putString(KEY_ACTIVE_ID, activeId ?: "")
            .apply()
    }

    private fun legacySingleAccount(): QuarkPanAccount? {
        val cookie = prefs.getString(KEY_COOKIE, null)
        if (cookie.isNullOrBlank()) return null
        val name = prefs.getString(KEY_USER_NAME, null) ?: ""
        return QuarkPanAccount(
            id = "quark:" + name.ifBlank { "account" },
            user = QuarkPanUser(name = name, avatarUrl = prefs.getString(KEY_AVATAR_URL, null)),
            cookie = cookie,
        )
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE_ID = "active_id"
        const val KEY_COOKIE = "cookie"
        const val KEY_USER_NAME = "user_name"
        const val KEY_AVATAR_URL = "avatar_url"
    }
}
