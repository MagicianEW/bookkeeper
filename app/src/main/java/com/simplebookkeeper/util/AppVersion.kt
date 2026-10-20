package com.simplebookkeeper.util

/**
 * 版本号显示规范化。
 *
 * build.gradle.kts 里的 versionName 写的是 "v0.4.7"（带 v 前缀），
 * 而历史版本的 UI 又自己拼了一个 "v"，于是启动页和关于页显示成了
 * "vv0.4.7"。这里统一处理，保证任何输入都恰好显示一个 v 前缀。
 */
object AppVersion {

    /**
     * 把 PackageManager 读回的 versionName 规范化为展示形式。
     *
     * "v0.4.7" → "v0.4.7"
     * "0.4.7"  → "v0.4.7"
     * ""       → ""
     */
    fun format(versionName: String?): String {
        val trimmed = versionName?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed else "v$trimmed"
    }
}
