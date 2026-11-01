package com.tubo.mcserver

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 世界/实例备份（更新日志第 22 条）。
 *
 * 备份文件放在实例目录的 `backups/` 下，按 `年-月-日-时-分-秒` 命名，
 * 同名的 `.desc` 文本保存用户填写的简介。
 */
object BackupManager {

    private const val DESC_SUFFIX = ".desc"

    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())

    fun list(inst: Instance): List<File> =
        inst.backupsDir().listFiles { f -> f.isFile && f.name.endsWith(".zip", true) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 备份实例目录（跳过 backups 自身），返回备份文件；失败返回 null */
    fun create(inst: Instance, name: String = timestamp(), desc: String = ""): File? {
        val root = inst.dir()
        if (!root.isDirectory) return null
        val target = File(inst.backupsDir(), "$name.zip")
        return try {
            ZipOutputStream(FileOutputStream(target)).use { zos ->
                root.walkTopDown()
                    .onEnter { it.absolutePath != inst.backupsDir().absolutePath }
                    .filter { it.isFile }
                    .forEach { f ->
                        val rel = f.relativeTo(root).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            writeDesc(target, desc)
            target
        } catch (e: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    /** 还原：解压覆盖到实例目录（不删原有文件，避免误伤） */
    fun restore(inst: Instance, backup: File): Boolean {
        if (!backup.isFile) return false
        return try {
            ZipInputStream(FileInputStream(backup)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(inst.dir(), entry.name)
                    if (!entry.isDirectory) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
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

    /**
     * 把备份复制到另一个实例的备份目录（含简介）。
     *
     * 重名时加 `-copy` 后缀，不覆盖对方已有的备份。
     */
    fun copyTo(backup: File, target: Instance): File? = runCatching {
        val dir = target.backupsDir().apply { mkdirs() }
        var dest = File(dir, backup.name)
        if (dest.exists()) {
            val base = backup.name.substringBeforeLast('.', backup.name)
            dest = File(dir, "$base-copy.zip")
            var i = 2
            while (dest.exists()) {
                dest = File(dir, "$base-copy$i.zip")
                i++
            }
        }
        backup.copyTo(dest, overwrite = false)
        // 简介跟着一起搬，否则还原后描述就丢了
        val desc = descFile(backup)
        if (desc.isFile) desc.copyTo(descFile(dest), overwrite = true)
        dest
    }.getOrNull()

    fun delete(backup: File): Boolean {
        descFile(backup).delete()
        return backup.delete()
    }

    fun rename(backup: File, newName: String): File? {
        val clean = newName.trim().removeSuffix(".zip")
        if (clean.isEmpty()) return null
        val target = File(backup.parentFile, "$clean.zip")
        if (target.exists()) return null
        if (!backup.renameTo(target)) return null
        descFile(backup).takeIf { it.exists() }?.renameTo(descFile(target))
        return target
    }

    fun description(backup: File): String =
        descFile(backup).takeIf { it.isFile }?.readText()?.trim() ?: ""

    fun writeDesc(backup: File, desc: String) {
        runCatching { descFile(backup).writeText(desc) }
    }

    fun sizeLabel(backup: File): String {
        val mb = backup.length() / 1024.0 / 1024.0
        return if (mb >= 1) "%.1f MB".format(mb) else "${backup.length() / 1024} KB"
    }

    /** 备份名转成可读时间：2026-10-31-08-02-13 → 2026-10-31 08:02:13 */
    fun prettyName(fileName: String): String {
        val base = fileName.removeSuffix(".zip")
        val p = base.split("-")
        return if (p.size >= 6) "${p[0]}-${p[1]}-${p[2]} ${p[3]}:${p[4]}:${p[5]}" else base
    }

    private fun descFile(backup: File): File =
        File(backup.parentFile, backup.name.removeSuffix(".zip") + DESC_SUFFIX)
}
