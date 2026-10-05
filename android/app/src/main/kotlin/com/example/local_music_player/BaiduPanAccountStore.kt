package com.example.local_music_player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 删除账号后应保持的激活账号 id：删除非激活账号保持原 active；删除激活账号则切到剩余第一个。 */
internal fun resolveActiveAfterRemove(activeId: String?, removedId: String, remainingIds: List<String>): String? = when {
    activeId != removedId -> activeId
    remainingIds.isEmpty() -> null
    else -> remainingIds.first()
}

/** 百度网盘账号（多账号模型）：凭证 + 用户信息，账号 id 在网盘内唯一。 */
internal data class BaiduPanAccount(
    val id: String,
    val user: BaiduPanUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
) {
    fun token(): BaiduPanToken = BaiduPanToken(accessToken, refreshToken, expiresAtMs)
}

/**
 * 百度网盘多账号本地持久化（SharedPreferences，随卸载清除）。
 * 同时维护“当前激活账号”，云端存储、播放与下载都使用激活账号；
 * 保留旧版单账号方法（saveToken/loadUser 等），它们操作当前激活账号以便兼容既有调用。
 */
internal class BaiduPanAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("baidu_pan_account", Context.MODE_PRIVATE)

    fun accounts(): List<BaiduPanAccount> {
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
                        BaiduPanAccount(
                            id = obj.getString("id"),
                            user = BaiduPanUser(
                                name = obj.getString("name"),
                                netdiskName = obj.optString("netdisk_name", ""),
                                vipType = obj.optInt("vip_type", 0),
                                avatarUrl = obj.optString("avatar_url", "").ifBlank { null },
                            ),
                            accessToken = obj.getString("access_token"),
                            refreshToken = obj.getString("refresh_token"),
                            expiresAtMs = obj.optLong("expires_at_ms", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE_ID, null)?.takeIf { it.isNotBlank() }

    fun activeAccount(): BaiduPanAccount? {
        val list = accounts()
        if (list.isEmpty()) return null
        val id = activeId()
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /** 新增或更新账号；若账号列表原本为空，则自动设为激活账号。 */
    fun saveAccount(account: BaiduPanAccount) {
        val current = accounts()
        val updated = if (current.any { it.id == account.id }) {
            current.map { if (it.id == account.id) account else it }
        } else {
            current + account
        }
        persist(updated, activeId() ?: account.id)
    }

    /** 登录成功后保存完整会话（凭证 + 用户信息）并切换为激活账号；同名账号视为重新登录覆盖。 */
    fun saveSession(token: BaiduPanToken, user: BaiduPanUser) {
        val id = "baidu:" + (user.name.ifBlank { "account" })
        saveAccount(
            BaiduPanAccount(
                id = id,
                user = user,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtMs = System.currentTimeMillis() + token.expiresInMs.coerceAtLeast(0),
            ),
        )
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

    fun saveToken(accessToken: String, refreshToken: String, expiresInMs: Long) {
        val existing = activeAccount()
        saveAccount(
            BaiduPanAccount(
                id = existing?.id ?: "baidu-default",
                user = existing?.user ?: BaiduPanUser("", "", 0, null),
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = System.currentTimeMillis() + expiresInMs.coerceAtLeast(0),
            ),
        )
    }

    fun loadToken(): BaiduPanToken? {
        val account = activeAccount() ?: return null
        if (account.accessToken.isBlank() || account.refreshToken.isBlank()) return null
        return account.token()
    }

    fun accessToken(): String = activeAccount()?.accessToken.orEmpty()

    fun expiresAtMs(): Long = activeAccount()?.expiresAtMs ?: 0L

    fun saveUser(user: BaiduPanUser) {
        val existing = activeAccount()
        saveAccount(
            BaiduPanAccount(
                id = existing?.id ?: ("baidu:" + user.name.ifBlank { "account" }),
                user = user,
                accessToken = existing?.accessToken.orEmpty(),
                refreshToken = existing?.refreshToken.orEmpty(),
                expiresAtMs = existing?.expiresAtMs ?: 0L,
            ),
        )
    }

    fun loadUser(): BaiduPanUser? = activeAccount()?.user

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun persist(accounts: List<BaiduPanAccount>, activeId: String?) {
        val array = JSONArray()
        accounts.forEach { acc ->
            array.put(
                JSONObject().apply {
                    put("id", acc.id)
                    put("name", acc.user.name)
                    put("netdisk_name", acc.user.netdiskName)
                    put("vip_type", acc.user.vipType)
                    put("avatar_url", acc.user.avatarUrl ?: "")
                    put("access_token", acc.accessToken)
                    put("refresh_token", acc.refreshToken)
                    put("expires_at_ms", acc.expiresAtMs)
                },
            )
        }
        prefs.edit()
            .putString(KEY_ACCOUNTS, array.toString())
            .putString(KEY_ACTIVE_ID, activeId ?: "")
            .apply()
    }

    private fun legacySingleAccount(): BaiduPanAccount? {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (access.isNullOrBlank() && refresh.isNullOrBlank()) return null
        val name = prefs.getString(KEY_USER_NAME, null) ?: ""
        return BaiduPanAccount(
            id = "baidu:" + name.ifBlank { "account" },
            user = BaiduPanUser(
                name = name,
                netdiskName = prefs.getString(KEY_NETDISK_NAME, null).orEmpty(),
                vipType = prefs.getInt(KEY_VIP_TYPE, 0),
                avatarUrl = prefs.getString(KEY_AVATAR_URL, null),
            ),
            accessToken = access.orEmpty(),
            refreshToken = refresh.orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L),
        )
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE_ID = "active_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        const val KEY_USER_NAME = "user_name"
        const val KEY_NETDISK_NAME = "netdisk_name"
        const val KEY_VIP_TYPE = "vip_type"
        const val KEY_AVATAR_URL = "avatar_url"
    }
}
