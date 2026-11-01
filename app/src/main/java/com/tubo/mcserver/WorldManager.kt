package com.tubo.mcserver

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 世界存档的读写（更新日志第 22 条）。
 *
 * 一个实例可能有三份世界目录：主世界、下界、末地，导出/删除时一起处理。
 */
object WorldManager {

    val WORLD_NAMES = listOf("world", "world_nether", "world_the_end")

    fun worlds(inst: Instance): List<File> =
        WORLD_NAMES.map { File(inst.dir(), it) }.filter { it.isDirectory }

    fun sizeOf(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    fun totalSize(inst: Instance): Long = worlds(inst).sumOf { sizeOf(it) }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** 导出世界为 zip（含 nether / the_end） */
    fun exportZip(inst: Instance, dest: File): Boolean {
        val dirs = worlds(inst)
        if (dirs.isEmpty()) return false
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                dirs.forEach { root ->
                    root.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(inst.dir()).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            dest.isFile && dest.length() > 0
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false
        }
    }

    /** 从 zip 导入世界（解压覆盖到实例目录，带路径穿越防护） */
    fun importZip(inst: Instance, zip: File): Boolean {
        if (!zip.isFile) return false
        val root = inst.dir().canonicalFile
        return try {
            ZipInputStream(FileInputStream(zip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name.isNotEmpty() && !name.contains("..")) {
                        val target = File(root, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            // 防 zip slip：目标必须在实例目录内
                            if (target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                                FileOutputStream(target).use { zis.copyTo(it) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除全部世界目录 */
    fun deleteWorlds(inst: Instance): Boolean {
        var ok = true
        WORLD_NAMES.forEach { name ->
            val dir = File(inst.dir(), name)
            if (dir.exists()) ok = runCatching { dir.deleteRecursively() }.getOrDefault(false) && ok
        }
        return ok
    }
}
