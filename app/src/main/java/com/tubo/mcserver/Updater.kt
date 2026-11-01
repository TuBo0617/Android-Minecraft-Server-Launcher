package com.tubo.mcserver

import org.json.JSONObject

/**
 * 版本更新检查（更新日志第 18 条）。
 *
 * 走 GitHub Releases 的 `latest` 接口。仓库还没发过 Release 时会返回 404，
 * 这种情况按「无法检查」处理，不要当成错误吓用户。
 */
object Updater {

    /** 当前版本，和「关于」页展示的一致 */
    const val CURRENT = "v0.1 beta"

    private const val OWNER = "tubo"
    private const val REPO = "Android-Minecraft-Server-Launcher"

    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    data class Result(
        val latestTag: String = "",
        val htmlUrl: String = RELEASES_PAGE,
        val notes: String = "",
        /** true 表示确实有新版本 */
        val hasUpdate: Boolean = false,
        /** 非空表示检查失败的原因 */
        val error: String? = null
    )

    /**
     * 查询最新发布。阻塞调用，请放在 IO 线程。
     */
    fun check(): Result {
        val url = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        val text = runCatching {
            Net.getString(url, headers = mapOf("Accept" to "application/vnd.github+json"))
        }.getOrElse { e ->
            return Result(error = "检查失败：${e.message ?: e.javaClass.simpleName}")
        }

        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result(error = "返回内容无法解析")

        o.optString("message").takeIf { it.isNotEmpty() }?.let {
            // 典型是 "Not Found"（仓库没发 Release）或 API 限流
            return Result(error = if (it.contains("Not Found", true)) "仓库还没有发布版本" else it)
        }

        val tag = o.optString("tag_name")
        if (tag.isEmpty()) return Result(error = "仓库还没有发布版本")

        return Result(
            latestTag = tag,
            htmlUrl = o.optString("html_url", RELEASES_PAGE),
            notes = o.optString("body").take(600),
            hasUpdate = isNewer(tag, CURRENT)
        )
    }

    /**
     * 比较版本号：抽出所有数字段逐个比。
     * `v0.1 beta` vs `v0.2` → 后者更新；`v0.1` vs `v0.1-beta` → 视为同级（不提示更新）。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = nums(latest)
        val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun nums(s: String): List<Int> =
        Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()
}
