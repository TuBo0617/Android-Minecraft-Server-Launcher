package com.tubo.mcserver

import java.io.File
import java.util.Properties

/**
 * 实例的持久化与查询。
 *
 * 每个实例是 `servers/<实例名>/` 一个目录，元信息写在目录内的 `instance.prop`。
 * 读取时对缺失字段一律回落到默认值，保证旧版本实例文件仍可用。
 */
object InstanceManager {

    private const val PROP = "instance.prop"

    fun root(): File = Storage.servers()

    fun list(): List<Instance> {
        val out = ArrayList<Instance>()
        root().listFiles()?.filter { it.isDirectory }?.forEach { d ->
            readProp(File(d, PROP))?.let { out += it }
        }
        // 星标优先，其次按自定义顺序，最后按名称
        return out.sortedWith(
            compareByDescending<Instance> { it.pinned }
                .thenBy { it.order }
                .thenBy { it.name }
        )
    }

    fun get(name: String): Instance? = list().firstOrNull { it.name == name }

    fun create(name: String, mcVersion: String, loader: String, core: String = ""): Instance {
        val inst = Instance(
            name = name,
            mcVersion = mcVersion,
            loader = loader,
            core = core.ifEmpty { loader },
            order = list().size
        )
        inst.dir().mkdirs()
        inst.modsDir()
        inst.pluginsDir()
        save(inst)
        return inst
    }

    /** 全字段落盘 */
    fun save(inst: Instance) {
        inst.dir().mkdirs()
        File(inst.dir(), PROP).outputStream().use { out ->
            Properties().apply {
                setProperty("name", inst.name)
                setProperty("mcVersion", inst.mcVersion)
                setProperty("loader", inst.loader)
                setProperty("core", inst.core)
                setProperty("loaderVersion", inst.loaderVersion)
                setProperty("pinned", inst.pinned.toString())
                setProperty("order", inst.order.toString())
                setProperty("iconPath", inst.iconPath)
                setProperty("javaOverride", inst.javaOverride.toString())
                setProperty("javaHome", inst.javaHome)
                setProperty("memoryOverride", inst.memoryOverride.toString())
                setProperty("minMemMb", inst.minMemMb.toString())
                setProperty("maxMemMb", inst.maxMemMb.toString())
                setProperty("noguiOverride", inst.noguiOverride.toString())
                setProperty("nogui", inst.nogui.toString())
                setProperty("argsOverride", inst.argsOverride.toString())
                setProperty("customArgs", inst.customArgs)
                setProperty("modpack", inst.modpack)
                setProperty("customJar", inst.customJar)
            }.store(out, null)
        }
    }

    fun update(inst: Instance) = save(inst)

    fun setPinned(inst: Instance, pinned: Boolean): Instance {
        val updated = inst.copy(pinned = pinned)
        save(updated)
        return updated
    }

    /** 拖拽重排序：把 [from] 位置的项目移动到 [to]，并重写全部 order */
    fun reorder(from: Int, to: Int) {
        val list = list().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        list.forEachIndexed { index, inst ->
            if (inst.order != index) save(inst.copy(order = index))
        }
    }

    /**
     * 改名（同时移动实例目录）。
     * @return 改名后的实例；目标名已存在或移动失败返回 null
     */
    fun rename(oldName: String, newName: String): Instance? {
        if (oldName == newName) return get(oldName)
        val old = get(oldName) ?: return null
        val target = File(root(), newName)
        if (target.exists()) return null
        if (!old.dir().renameTo(target)) return null
        val updated = old.copy(name = newName)
        save(updated)
        return updated
    }

    fun delete(inst: Instance) {
        inst.dir().deleteRecursively()
    }

    /** 实例名合法性：不允许路径分隔符与 Windows/Unix 保留字符 */
    fun validateName(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "实例名不能为空"
            n.length > 32 -> "实例名不能超过 32 个字符"
            n.any { it in "\\/:*?\"<>|" } -> "实例名不能包含 \\ / : * ? \" < > |"
            n == "." || n == ".." -> "实例名不合法"
            else -> null
        }
    }

    private fun readProp(f: File): Instance? {
        if (!f.isFile) return null
        return try {
            val p = Properties()
            f.inputStream().use { p.load(it) }
            val name = p.getProperty("name")?.takeIf { it.isNotBlank() }
                ?: f.parentFile?.name
                ?: return null
            Instance(
                name = name,
                mcVersion = p.getProperty("mcVersion", ""),
                loader = p.getProperty("loader", "VANILLA"),
                core = p.getProperty("core", ""),
                loaderVersion = p.getProperty("loaderVersion", ""),
                pinned = p.getProperty("pinned", "false").toBoolean(),
                order = p.getProperty("order", "0").toIntOrNull() ?: 0,
                iconPath = p.getProperty("iconPath", ""),
                javaOverride = p.getProperty("javaOverride", "false").toBoolean(),
                javaHome = p.getProperty("javaHome", ""),
                memoryOverride = p.getProperty("memoryOverride", "false").toBoolean(),
                minMemMb = p.getProperty("minMemMb", "256").toIntOrNull() ?: 256,
                maxMemMb = p.getProperty("maxMemMb", "2048").toIntOrNull() ?: 2048,
                noguiOverride = p.getProperty("noguiOverride", "false").toBoolean(),
                nogui = p.getProperty("nogui", "true").toBoolean(),
                argsOverride = p.getProperty("argsOverride", "false").toBoolean(),
                customArgs = p.getProperty("customArgs", ""),
                modpack = p.getProperty("modpack", ""),
                customJar = p.getProperty("customJar", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
