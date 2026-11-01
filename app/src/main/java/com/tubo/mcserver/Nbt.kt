package com.tubo.mcserver

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * NBT（Named Binary Tag）读写。
 *
 * Minecraft 的 `level.dat`、`playerdata` 下的 `.dat` 都是 **gzip 压缩的 NBT**，
 * 结构是一棵以 Compound 为根的树。这里只做「读成树 → 改标量 → 原样写回」，
 * 不做任何字段语义解释——版本差异都在字段里，不需要为每个版本写适配代码。
 *
 * 大端序，字符串为 `u16 长度 + UTF-8`。
 */
object Nbt {

    const val TAG_END = 0
    const val TAG_BYTE = 1
    const val TAG_SHORT = 2
    const val TAG_INT = 3
    const val TAG_LONG = 4
    const val TAG_FLOAT = 5
    const val TAG_DOUBLE = 6
    const val TAG_BYTE_ARRAY = 7
    const val TAG_STRING = 8
    const val TAG_LIST = 9
    const val TAG_COMPOUND = 10
    const val TAG_INT_ARRAY = 11
    const val TAG_LONG_ARRAY = 12

    sealed class Tag {
        abstract val id: Int

        class TByte(var v: Byte) : Tag() { override val id = TAG_BYTE }
        class TShort(var v: Short) : Tag() { override val id = TAG_SHORT }
        class TInt(var v: Int) : Tag() { override val id = TAG_INT }
        class TLong(var v: Long) : Tag() { override val id = TAG_LONG }
        class TFloat(var v: Float) : Tag() { override val id = TAG_FLOAT }
        class TDouble(var v: Double) : Tag() { override val id = TAG_DOUBLE }
        class TByteArray(val v: ByteArray) : Tag() { override val id = TAG_BYTE_ARRAY }
        class TString(var v: String) : Tag() { override val id = TAG_STRING }
        class TList(var elemId: Int, val items: MutableList<Tag> = mutableListOf()) : Tag() {
            override val id = TAG_LIST
        }
        class TCompound(val map: MutableMap<String, Tag> = LinkedHashMap()) : Tag() {
            override val id = TAG_COMPOUND
        }
        class TIntArray(val v: IntArray) : Tag() { override val id = TAG_INT_ARRAY }
        class TLongArray(val v: LongArray) : Tag() { override val id = TAG_LONG_ARRAY }
    }

    /** 可编辑的标量类型（数组、列表、Compound 不在其中） */
    fun isEditable(tag: Tag): Boolean = when (tag) {
        is Tag.TByte, is Tag.TShort, is Tag.TInt, is Tag.TLong,
        is Tag.TFloat, is Tag.TDouble, is Tag.TString -> true
        else -> false
    }

    fun typeName(id: Int): String = when (id) {
        TAG_END -> "End"
        TAG_BYTE -> "Byte"
        TAG_SHORT -> "Short"
        TAG_INT -> "Int"
        TAG_LONG -> "Long"
        TAG_FLOAT -> "Float"
        TAG_DOUBLE -> "Double"
        TAG_BYTE_ARRAY -> "ByteArray"
        TAG_STRING -> "String"
        TAG_LIST -> "List"
        TAG_COMPOUND -> "Compound"
        TAG_INT_ARRAY -> "IntArray"
        TAG_LONG_ARRAY -> "LongArray"
        else -> "Unknown($id)"
    }

    /** 列表/数组在界面上的一行摘要 */
    fun preview(tag: Tag): String = when (tag) {
        is Tag.TByte -> tag.v.toString()
        is Tag.TShort -> tag.v.toString()
        is Tag.TInt -> tag.v.toString()
        is Tag.TLong -> tag.v.toString()
        is Tag.TFloat -> tag.v.toString()
        is Tag.TDouble -> tag.v.toString()
        is Tag.TString -> if (tag.v.length > 60) tag.v.take(60) + "…" else tag.v
        is Tag.TByteArray -> "${tag.v.size} 字节"
        is Tag.TIntArray -> "${tag.v.size} 个 int"
        is Tag.TLongArray -> "${tag.v.size} 个 long"
        is Tag.TList -> "${tag.items.size} 项（${typeName(tag.elemId)}）"
        is Tag.TCompound -> "${tag.map.size} 个子项"
    }

    /** 把标量转成可编辑文本 */
    fun asText(tag: Tag): String = when (tag) {
        is Tag.TByte -> tag.v.toString()
        is Tag.TShort -> tag.v.toString()
        is Tag.TInt -> tag.v.toString()
        is Tag.TLong -> tag.v.toString()
        is Tag.TFloat -> tag.v.toString()
        is Tag.TDouble -> tag.v.toString()
        is Tag.TString -> tag.v
        else -> ""
    }

    /** 按原类型把文本写回标量；解析失败返回 false */
    fun fromText(tag: Tag, text: String): Boolean = runCatching {
        val t = text.trim()
        when (tag) {
            is Tag.TByte -> { tag.v = t.toByte(); true }
            is Tag.TShort -> { tag.v = t.toShort(); true }
            is Tag.TInt -> { tag.v = t.toInt(); true }
            is Tag.TLong -> { tag.v = t.toLong(); true }
            is Tag.TFloat -> { tag.v = t.toFloat(); true }
            is Tag.TDouble -> { tag.v = t.toDouble(); true }
            // 字符串不 trim：前后空格可能就是用户想保留的内容
            is Tag.TString -> { tag.v = text; true }
            else -> false
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------ 读写

    class Document(val name: String, val root: Tag.TCompound, val gzipped: Boolean)

    /** 读取文件；失败抛异常，由调用方提示 */
    fun read(file: File): Document {
        val raw = file.readBytes()
        val gz = raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()
        val stream = if (gz) GZIPInputStream(ByteArrayInputStream(raw)) else ByteArrayInputStream(raw)
        DataInputStream(BufferedInputStream(stream)).use { input ->
            val type = input.readUnsignedByte()
            if (type != TAG_COMPOUND) {
                throw IllegalStateException("根标签不是 Compound（实际 ${typeName(type)}）")
            }
            val name = readString(input)
            val root = readCompound(input)
            return Document(name, root, gz)
        }
    }

    /** 写回文件；会先备份成 `.bak` 再覆盖 */
    fun write(file: File, doc: Document) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(bytes)).use { out ->
            out.writeByte(TAG_COMPOUND)
            writeString(out, doc.name)
            writeCompound(out, doc.root)
        }
        val payload = bytes.toByteArray()
        val data = if (doc.gzipped) {
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(payload) }
            gz.toByteArray()
        } else payload

        runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
        FileOutputStream(file).use { it.write(data) }
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readUnsignedShort()
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readCompound(input: DataInputStream): Tag.TCompound {
        val map = LinkedHashMap<String, Tag>()
        while (true) {
            val id = input.readUnsignedByte()
            if (id == TAG_END) break
            val name = readString(input)
            map[name] = readPayload(input, id)
        }
        return Tag.TCompound(map)
    }

    private fun readPayload(input: DataInputStream, id: Int): Tag = when (id) {
        TAG_BYTE -> Tag.TByte(input.readByte())
        TAG_SHORT -> Tag.TShort(input.readShort())
        TAG_INT -> Tag.TInt(input.readInt())
        TAG_LONG -> Tag.TLong(input.readLong())
        TAG_FLOAT -> Tag.TFloat(input.readFloat())
        TAG_DOUBLE -> Tag.TDouble(input.readDouble())
        TAG_BYTE_ARRAY -> {
            val n = input.readInt()
            if (n < 0) throw IllegalStateException("ByteArray 长度非法: $n")
            val buf = ByteArray(n)
            input.readFully(buf)
            Tag.TByteArray(buf)
        }
        TAG_STRING -> Tag.TString(readString(input))
        TAG_LIST -> {
            val elem = input.readUnsignedByte()
            val n = input.readInt()
            if (n < 0) throw IllegalStateException("List 长度非法: $n")
            val items = ArrayList<Tag>(n)
            for (i in 0 until n) items += readPayload(input, elem)
            Tag.TList(elem, items)
        }
        TAG_COMPOUND -> readCompound(input)
        TAG_INT_ARRAY -> {
            val n = input.readInt()
            if (n < 0) throw IllegalStateException("IntArray 长度非法: $n")
            val arr = IntArray(n) { input.readInt() }
            Tag.TIntArray(arr)
        }
        TAG_LONG_ARRAY -> {
            val n = input.readInt()
            if (n < 0) throw IllegalStateException("LongArray 长度非法: $n")
            val arr = LongArray(n) { input.readLong() }
            Tag.TLongArray(arr)
        }
        else -> throw IllegalStateException("未知标签类型 $id")
    }

    private fun writeCompound(out: DataOutputStream, compound: Tag.TCompound) {
        compound.map.forEach { (name, tag) ->
            out.writeByte(tag.id)
            writeString(out, name)
            writePayload(out, tag)
        }
        out.writeByte(TAG_END)
    }

    private fun writePayload(out: DataOutputStream, tag: Tag) {
        when (tag) {
            is Tag.TByte -> out.writeByte(tag.v.toInt())
            is Tag.TShort -> out.writeShort(tag.v.toInt())
            is Tag.TInt -> out.writeInt(tag.v)
            is Tag.TLong -> out.writeLong(tag.v)
            is Tag.TFloat -> out.writeFloat(tag.v)
            is Tag.TDouble -> out.writeDouble(tag.v)
            is Tag.TByteArray -> {
                out.writeInt(tag.v.size)
                out.write(tag.v)
            }
            is Tag.TString -> writeString(out, tag.v)
            is Tag.TList -> {
                out.writeByte(tag.elemId)
                out.writeInt(tag.items.size)
                tag.items.forEach { writePayload(out, it) }
            }
            is Tag.TCompound -> writeCompound(out, tag)
            is Tag.TIntArray -> {
                out.writeInt(tag.v.size)
                tag.v.forEach { out.writeInt(it) }
            }
            is Tag.TLongArray -> {
                out.writeInt(tag.v.size)
                tag.v.forEach { out.writeLong(it) }
            }
        }
    }

    /**
     * 列出实例里可编辑的 NBT 文件。
     *
     * 位置随版本变化：`level.dat` 一直在世界根；玩家数据在 `playerdata/`；
     * 1.13+ 的结构数据在 `data/`。三个都扫一遍即可覆盖常见版本。
     */
    fun listFiles(inst: Instance): List<File> {
        val out = ArrayList<File>()
        val world = File(inst.dir(), "world")
        File(world, "level.dat").takeIf { it.isFile }?.let { out += it }
        File(world, "playerdata").listFiles { f -> f.isFile && f.name.endsWith(".dat") }
            ?.sortedBy { it.name }?.let { out += it }
        File(world, "data").listFiles { f -> f.isFile && f.name.endsWith(".dat") }
            ?.sortedBy { it.name }?.let { out += it }
        return out
    }

    /** 界面上的短名称 */
    fun labelOf(file: File, inst: Instance): String {
        val rel = runCatching { file.relativeTo(File(inst.dir(), "world")).path }.getOrNull() ?: file.name
        return rel.replace('\\', '/')
    }
}
