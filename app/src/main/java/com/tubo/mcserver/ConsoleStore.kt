package com.tubo.mcserver

/**
 * 控制台输出仓库。
 *
 * 每个标签页一份缓冲区：键是实例名；独立终端用 `SHELL_KEY`。
 * 只放内存（上限 3000 行），足够回溯一次开服过程，也不会把存储写爆。
 */
object ConsoleStore {

    const val SHELL_KEY = "__shell__"

    private const val MAX_LINES = 3000

    private val buffers = HashMap<String, ArrayDeque<String>>()

    @Volatile
    var version: Int = 0
        private set

    fun append(key: String, line: String) {
        synchronized(buffers) {
            val q = buffers.getOrPut(key) { ArrayDeque() }
            line.split('\n').forEach { l ->
                if (l.isNotEmpty()) {
                    q.addLast(l)
                    while (q.size > MAX_LINES) q.removeFirst()
                }
            }
            version++
        }
    }

    fun lines(key: String): List<String> = synchronized(buffers) {
        buffers[key]?.toList() ?: emptyList()
    }

    fun text(key: String): String = lines(key).joinToString("\n")

    fun clear(key: String) {
        synchronized(buffers) {
            buffers[key]?.clear()
            version++
        }
    }

    fun drop(key: String) {
        synchronized(buffers) {
            buffers.remove(key)
            version++
        }
    }
}
