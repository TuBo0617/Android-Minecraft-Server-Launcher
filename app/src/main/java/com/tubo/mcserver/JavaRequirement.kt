package com.tubo.mcserver

import java.io.File

/**
 * Minecraft 服务端版本与 Java 主版本的适配关系（更新日志第 4、12 条）。
 *
 * 启动器只提供 17 / 21 / 25 三个运行时（Termux 仓库已移除 8 和 11），
 * 所以这里返回的是**最低要求**，实际挑选时取「满足要求里最小的那个」——
 * 用 Java 25 跑 1.20.1 反而容易出问题，能低就低。
 */
object JavaRequirement {

    /** 该 MC 版本要求的最低 Java 主版本 */
    fun requiredMajor(mcVersion: String): Int {
        val v = mcVersion.trim().removePrefix("v")
        val m = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(v) ?: return 17
        val major = m.groupValues[1].toIntOrNull() ?: return 17
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        val patch = m.groupValues[3].toIntOrNull() ?: 0

        // 非 1.x 的版本号（例如 26.3）属于未来大版本，按最新要求处理
        if (major != 1) return 21

        return when {
            minor >= 21 -> 21
            minor == 20 && patch >= 5 -> 21
            minor >= 17 -> 17
            else -> 8
        }
    }

    /** 人话描述 */
    fun describe(mcVersion: String): String = when (val need = requiredMajor(mcVersion)) {
        8 -> "Java 8（启动器只能装 17/21/25，老版本可能跑不起来）"
        11 -> "Java 11"
        17 -> "Java 17"
        21 -> "Java 21"
        else -> "Java $need"
    }

    /**
     * 从安装目录 / 标签里解析出 Java 主版本。
     *
     * 注意要扫**整条路径**：实例独立指定时传进来的可能是 `.../openjdk-17/bin/java`，
     * 只看文件名只会得到 "java"，解析出 0，进而误报「Java 0 低于要求」。
     */
    fun majorOf(label: String, home: File): Int {
        val candidates = listOf(label, home.name, home.path)
        for (text in candidates) {
            Regex("(?:openjdk-|java-|jdk-?)(\\d+)").find(text)?.let {
                return it.groupValues[1].toIntOrNull() ?: 0
            }
        }
        // 兜底：路径里任意一段数字（例如 /jdk/21.0.1）
        Regex("(\\d+)").find(home.path)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }
}

/** 挑选结果：bin 为空表示没有可用 Java */
data class JavaPick(
    val bin: File?,
    val major: Int = 0,
    /** 非空表示有需要提示用户的情况（版本不匹配、回落到默认等） */
    val warning: String? = null
)

/**
 * 为某个 MC 版本挑一个合适的 Java。
 *
 * 规则：
 * 1. 实例指定了 Java（`javaOverride`）且该版本够用 → 直接用；
 * 2. 否则在已安装的 Java 里挑**满足最低要求且主版本最小**的那个；
 * 3. 都不满足时，退回到当前生效的 Java 并给出警告（让用户自己决定要不要继续）。
 */
fun JdkManager.pickFor(mcVersion: String, overrideHome: String = ""): JavaPick {
    val need = JavaRequirement.requiredMajor(mcVersion)
    val all = listInstalled().filter { it.exists }

    // 1) 实例独立指定
    if (overrideHome.isNotEmpty()) {
        val f = File(overrideHome)
        val bin = if (f.isFile) f else File(f, "bin/java")
        if (bin.exists()) {
            val major = JavaRequirement.majorOf("", f)
            val warn = if (major in 1 until need) {
                "实例指定的 Java $major 低于该版本要求的 Java $need，可能启动失败"
            } else null
            return JavaPick(bin, major, warn)
        }
    }

    if (all.isEmpty()) {
        return JavaPick(null, 0, "未检测到任何 Java，请到「设置 → Java」安装")
    }

    // 2) 满足要求里最小的
    val ok = all
        .map { it to JavaRequirement.majorOf(it.label, it.home) }
        .filter { (_, major) -> major >= need }
        .minByOrNull { (_, major) -> major }
    if (ok != null) return JavaPick(ok.first.bin, ok.second)

    // 3) 没有满足要求的，回落并警告
    val fallback = activeBin()
    val major = all.maxOfOrNull { JavaRequirement.majorOf(it.label, it.home) } ?: 0
    return JavaPick(
        fallback,
        major,
        "该版本需要 ${JavaRequirement.describe(mcVersion)}，但当前只装了 Java $major，可能无法启动"
    )
}

/**
 * 已安装 Java 的主版本列表，带 2 秒缓存。
 *
 * `listInstalled()` 要扫目录，而实例列表每张卡片都会问一次；
 * 不缓存的话，实例一多就会在组合阶段反复读磁盘。
 */
private object JavaMajorCache {
    var at: Long = 0
    var majors: List<Int> = emptyList()
}

fun JdkManager.installedMajors(): List<Int> {
    val now = System.currentTimeMillis()
    if (now - JavaMajorCache.at < 2000) return JavaMajorCache.majors
    val list = runCatching {
        listInstalled().filter { it.exists }.map { JavaRequirement.majorOf(it.label, it.home) }
    }.getOrDefault(emptyList())
    JavaMajorCache.at = now
    JavaMajorCache.majors = list
    return list
}

/** 是否有能满足该 MC 版本的 Java（界面用来提前给警告） */
fun JdkManager.hasCompatible(mcVersion: String): Boolean {
    val need = JavaRequirement.requiredMajor(mcVersion)
    return installedMajors().any { it >= need }
}
