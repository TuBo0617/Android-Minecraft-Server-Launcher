package com.tubo.mcserver.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.tubo.mcserver.App
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 背景类型（更新日志第 20 条） */
enum class BgType(val label: String) {
    NONE("无"),
    COLOR("纯色"),
    GRADIENT("渐变"),
    IMAGE("图片")
}

/**
 * 背景配置。
 *
 * `opacity` 只作用于图片，让壁纸不至于盖住文字；纯色/渐变本身就是用户选的，不需要再淡。
 */
data class BgConfig(
    val type: BgType = BgType.NONE,
    val color1: Int = 0xFF14161B.toInt(),
    val color2: Int = 0xFF2B2F3A.toInt(),
    val angle: Int = 135,
    val imagePath: String = "",
    val opacity: Int = 100
)

/**
 * 背景读写。启动器背景与终端背景各存一份，互不影响。
 *
 * 单独用一份 SharedPreferences，避免主配置里塞一堆背景字段。
 */
object AppBackgrounds {

    const val LAUNCHER = "launcher"
    const val CONSOLE = "console"

    /**
     * 配置版本号。改一次 +1，界面读它做 key 触发重组——
     * 否则设置里改完背景，要重启应用才生效。
     */
    var version by mutableStateOf(0)
        private set

    private fun sp() = App.ctx.getSharedPreferences("mcsl_bg", Context.MODE_PRIVATE)

    fun get(scope: String): BgConfig {
        val p = sp()
        val type = runCatching {
            BgType.valueOf(p.getString("${scope}_type", BgType.NONE.name) ?: BgType.NONE.name)
        }.getOrDefault(BgType.NONE)
        return BgConfig(
            type = type,
            color1 = p.getInt("${scope}_c1", 0xFF14161B.toInt()),
            color2 = p.getInt("${scope}_c2", 0xFF2B2F3A.toInt()),
            angle = p.getInt("${scope}_angle", 135),
            imagePath = p.getString("${scope}_img", "") ?: "",
            opacity = p.getInt("${scope}_alpha", 100).coerceIn(0, 100)
        )
    }

    fun set(scope: String, cfg: BgConfig) {
        sp().edit()
            .putString("${scope}_type", cfg.type.name)
            .putInt("${scope}_c1", cfg.color1)
            .putInt("${scope}_c2", cfg.color2)
            .putInt("${scope}_angle", cfg.angle)
            .putString("${scope}_img", cfg.imagePath)
            .putInt("${scope}_alpha", cfg.opacity)
            .apply()
        version++
    }
}

/**
 * 背景层。
 *
 * 用 `drawBehind` 而不是 `Box(background=...)`：渐变要按实际尺寸算角度，
 * 图片还要做居中裁剪，直接画最省事。
 */
@Composable
fun BackgroundBox(
    config: BgConfig,
    modifier: Modifier = Modifier,
    fallback: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val bitmap = remember(config.imagePath, config.type) {
        if (config.type == BgType.IMAGE) loadScaled(config.imagePath) else null
    }

    Box(
        modifier = modifier.drawBehind {
            when (config.type) {
                BgType.NONE -> if (fallback != Color.Unspecified) drawRect(fallback)
                BgType.COLOR -> drawRect(Color(config.color1))
                BgType.GRADIENT -> {
                    val rad = Math.toRadians(config.angle.toDouble())
                    val dx = cos(rad).toFloat()
                    val dy = sin(rad).toFloat()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val len = (size.width * abs(dx) + size.height * abs(dy)) / 2f
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(config.color1), Color(config.color2)),
                            start = Offset(cx - dx * len, cy - dy * len),
                            end = Offset(cx + dx * len, cy + dy * len)
                        )
                    )
                }
                // 图片走下面的 Image 组合（ContentScale.Crop 就是居中裁剪），
                // 这里只负责铺一层兜底色，图还没解码完时不至于露白
                BgType.IMAGE -> if (fallback != Color.Unspecified) drawRect(fallback)
            }
        }
    ) {
        if (config.type == BgType.IMAGE && bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = (config.opacity / 100f).coerceIn(0f, 1f),
                modifier = Modifier.matchParentSize()
            )
        }
        content()
    }
}

/**
 * 把相册/文件里选的图片拷进应用私有目录，返回落地路径。
 *
 * 不直接存 SAF 的 Uri：授权是临时的，重启后可能读不到，背景就没了。
 */
fun importBackgroundImage(ctx: Context, uri: android.net.Uri): String? {
    val dir = File(App.ctx.filesDir, "backgrounds").apply { mkdirs() }
    val dest = File(dir, "bg-${System.currentTimeMillis()}.img")
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (dest.length() == 0L) {
            dest.delete()
            return null
        }
        dest.absolutePath
    }.getOrNull()
}

/** 把选中的音频拷进应用私有目录（SAF 授权是临时的，存路径才稳） */
fun importBackgroundAudio(ctx: Context, uri: android.net.Uri): String? {
    val dir = File(App.ctx.filesDir, "audio").apply { mkdirs() }
    val dest = File(dir, "bgm-${System.currentTimeMillis()}.audio")
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (dest.length() == 0L) {
            dest.delete()
            return null
        }
        dest.absolutePath
    }.getOrNull()
}

/**
 * 按目标宽度解码，避免把 4000×4000 的原图整个塞进内存。
 * 解码失败（文件被删/权限不足）返回 null，界面自动回落到纯色底。
 */
private fun loadScaled(path: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isEmpty()) return null
    val f = File(path)
    if (!f.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val target = 1080
        while (bounds.outWidth / (sample * 2) >= target && sample < 16) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }.getOrNull()
}
