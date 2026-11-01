package com.tubo.mcserver.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 极简远程图片加载器（不引入 Coil，省一个依赖）。
 *
 * 内存 LRU + 4 线程池；加载完成后自增 [version]，Compose 读它触发重组。
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "img-load").apply { isDaemon = true }
    }

    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** 已加载完成的计数，供界面订阅 */
    var version by mutableStateOf(0)
        private set

    fun bitmap(url: String): Bitmap? = if (url.isEmpty()) null else cache.get(url)

    fun load(url: String) {
        if (url.isEmpty()) return
        if (cache.get(url) != null) return
        if (!loading.add(url)) return
        pool.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MCServerLauncher/1.0")
                }
                conn.inputStream.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    if (bmp != null) cache.put(url, bmp)
                }
                runCatching { conn.disconnect() }
            } catch (_: Exception) {
                // 图片失败不影响主流程，静默忽略
            } finally {
                loading.remove(url)
                version++
            }
        }
    }
}

/** 圆角远程图片；没有地址或加载失败时显示占位 */
@Composable
fun RemoteImage(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp
) {
    @Suppress("UNUSED_EXPRESSION")
    ImageLoader.version

    LaunchedEffect(url) { ImageLoader.load(url) }
    val bmp = ImageLoader.bitmap(url)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "?",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
