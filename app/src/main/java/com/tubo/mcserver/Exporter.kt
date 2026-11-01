package com.tubo.mcserver

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 实例导出（更新日志第 23 条）。
 *
 * 产出标准 zip：可选是否带上服务端核心、mods、资源包、世界与备份。
 * `manifest.json` 记录实例元信息与 mod/资源包清单，便于后续自动补齐缺失资源。
 */
object Exporter {

    data class Options(
        val includeCore: Boolean = false,
        val includeMods: Boolean = true,
        val includeResourcePacks: Boolean = true,
        val includeWorld: Boolean = true,
        val includeBackups: Boolean = false,
        val includeConfig: Boolean = true
    )

    /** 导出到下载目录，返回产出文件；失败返回 null */
    fun export(inst: Instance, opts: Options): File? {
        val root = inst.dir()
        if (!root.isDirectory) return null
        val out = File(Storage.downloads(), "${inst.name}-${BackupManager.timestamp()}.zip")
        return try {
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                root.walkTopDown()
                    .onEnter { dir ->
                        when (dir.name) {
                            "backups" -> opts.includeBackups
                            "mods" -> opts.includeMods
                            "resourcepacks" -> opts.includeResourcePacks
                            "world", "world_nether", "world_the_end" -> opts.includeWorld
                            "libraries", "versions", "cache" -> false
                            else -> true
                        }
                    }
                    .filter { it.isFile }
                    .filter { f ->
                        if (opts.includeConfig) true
                        else !f.name.endsWith(".properties") && !f.name.endsWith(".yml")
                    }
                    .filter { f ->
                        if (opts.includeCore) true
                        else f.extension.lowercase() != "jar" || f.parentFile != root
                    }
                    .forEach { f ->
                        val rel = f.relativeTo(root).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }

                // manifest
                val manifest = buildString {
                    append("{\n")
                    append("  \"name\": \"${inst.name}\",\n")
                    append("  \"mcVersion\": \"${inst.mcVersion}\",\n")
                    append("  \"loader\": \"${inst.loader}\",\n")
                    append("  \"core\": \"${inst.core}\",\n")
                    append("  \"mods\": ${jsonArray(inst.modsDir())},\n")
                    append("  \"resourcePacks\": ${jsonArray(inst.resourcePacksDir())}\n")
                    append("}\n")
                }
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray())
                zos.closeEntry()
            }
            out
        } catch (e: Exception) {
            runCatching { out.delete() }
            null
        }
    }

    private fun jsonArray(dir: File): String {
        val names = dir.listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()
        return names.joinToString(",", "[", "]") { "\"$it\"" }
    }

    /**
     * 导出为 `.mrpack`（Modrinth 整合包格式，更新日志第 23 条）。
     *
     * ## 为什么 files 是空的
     *
     * mrpack 的 `files[]` 要求每项带 `downloads` 直链和 sha1/sha512 校验值。
     * 我们在安装模组时只落了文件，没有记录来源 URL 和哈希，硬编是编不出来的。
     * 所以这里采用规范允许的另一条路：**把资源整体放进 `overrides/`**——
     * 启动器会原样拷进 `.minecraft`，同样能装起来，只是包会大一些。
     *
     * 以后若在下载时把 `downloads`/`hashes` 一并记到模组索引里，
     * 就可以改成「只写元信息、安装时在线补齐」的轻量包。
     */
    fun exportMrpack(inst: Instance, opts: Options): File? {
        val root = inst.dir()
        if (!root.isDirectory) return null
        if (inst.mcVersion.isBlank()) return null

        val out = File(Storage.downloads(), "${inst.name}-${BackupManager.timestamp()}.mrpack")
        return try {
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                val stamp = BackupManager.timestamp()
                val index = buildString {
                    append("{\n")
                    append("  \"formatVersion\": 1,\n")
                    append("  \"game\": \"minecraft\",\n")
                    append("  \"versionId\": ${jsonStr(stamp)},\n")
                    append("  \"name\": ${jsonStr(inst.name)},\n")
                    append("  \"summary\": ${jsonStr("由 MCServerLauncher 导出的服务端整合包")},\n")
                    append("  \"files\": [],\n")
                    append("  \"dependencies\": {")
                    append("\"minecraft\": ${jsonStr(inst.mcVersion)}")
                    // 加载器版本没记录时宁可不写，也不要写一个假的版本号
                    loaderDependency(inst)?.let { (k, v) ->
                        append(", ${jsonStr(k)}: ${jsonStr(v)}")
                    }
                    append("}\n}")
                }
                zos.putNextEntry(ZipEntry("modrinth.index.json"))
                zos.write(index.toByteArray())
                zos.closeEntry()

                root.walkTopDown()
                    .onEnter { dir ->
                        when (dir.name) {
                            "backups" -> opts.includeBackups
                            "mods" -> opts.includeMods
                            "resourcepacks" -> opts.includeResourcePacks
                            "world", "world_nether", "world_the_end" -> opts.includeWorld
                            "libraries", "versions", "cache" -> false
                            else -> true
                        }
                    }
                    .filter { it.isFile }
                    // 核心 jar 默认不带（mrpack 里不该有服务端本体）
                    .filter { f -> opts.includeCore || f.extension.lowercase() != "jar" || f.parentFile != root }
                    .forEach { f ->
                        val rel = f.relativeTo(root).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry("overrides/$rel"))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            out
        } catch (e: Exception) {
            runCatching { out.delete() }
            null
        }
    }

    /** mrpack 里加载器的键名，和 ModpackInstaller 解析时保持一致 */
    private fun loaderDependency(inst: Instance): Pair<String, String>? {
        val ver = inst.loaderVersion.trim()
        if (ver.isEmpty()) return null
        val key = when (inst.loader.uppercase()) {
            "FABRIC" -> "fabric-loader"
            "QUILT" -> "quilt-loader"
            "FORGE" -> "forge"
            "NEOFORGE", "NEOFORGED" -> "neoforge"
            else -> return null
        }
        return key to ver
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
