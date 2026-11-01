package com.tubo.mcserver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubo.mcserver.Prefs

/**
 * 移植自参考项目 ZL2 的色板结构。
 *
 * ZL2 的 ColorTheme 是「7 个色位」的容器，每套色板一份；主题类型见 [ZThemeType]。
 * 这里保留同样的结构，另外只为默认色板（余烬）给出完整角色映射，
 * 其余色板以 primary 系列驱动 Material3，避免逐色搬运近 3000 行数据。
 */

/** 一套色板的 7 个色位 */
data class ZColorTheme(
    val embermire: Color,
    val velvetRose: Color,
    val mistwave: Color,
    val glacier: Color,
    val verdantField: Color,
    val urbanAsh: Color,
    val verdantDawn: Color
)

enum class ZThemeType(val label: String) {
    DYNAMIC("跟随系统"),
    EMBERMIRE("余烬"),
    VELVET_ROSE("丝绒玫瑰"),
    MISTWAVE("雾浪"),
    GLACIER("冰川"),
    VERDANTFIELD("碧野"),
    URBAN_ASH("城市灰"),
    VERDANT_DAWN("碧晓"),
    CUSTOM("自定义");

    val isBuiltin: Boolean get() = this != DYNAMIC && this != CUSTOM
}

object ZPalettes {

    /** 各色板的主色（浅色 / 深色），取自 ZL2 Color.kt 的 primary 行 */
    data class Anchor(val light: Color, val dark: Color, val containerLight: Color, val containerDark: Color)

    val embermire = Anchor(Color(0xFFA63A17), Color(0xFFFFB59F), Color(0xFFFE7A52), Color(0xFFC4502B))
    val velvetRose = Anchor(Color(0xFF723D57), Color(0xFFF9B2D2), Color(0xFF9B607C), Color(0xFF915873))
    val mistwave = Anchor(Color(0xFF426469), Color(0xFFCEF3F9), Color(0xFFB1D5DB), Color(0xFFA5C9CF))
    val glacier = Anchor(Color(0xFF006684), Color(0xFF73D2FB), Color(0xFF4CAFD6), Color(0xFF007EA2))
    val verdantField = Anchor(Color(0xFF676014), Color(0xFFD2C972), Color(0xFFEFE58B), Color(0xFF4E4800))
    val urbanAsh = Anchor(Color(0xFF5E5E5F), Color(0xFFC7C6C6), Color(0xFFAEAEAE), Color(0xFF9B9B9B))
    val verdantDawn = Anchor(Color(0xFF004814), Color(0xFF8ED88E), Color(0xFF276E31), Color(0xFF005219))

    fun anchorOf(type: ZThemeType): Anchor = when (type) {
        ZThemeType.VELVET_ROSE -> velvetRose
        ZThemeType.MISTWAVE -> mistwave
        ZThemeType.GLACIER -> glacier
        ZThemeType.VERDANTFIELD -> verdantField
        ZThemeType.URBAN_ASH -> urbanAsh
        ZThemeType.VERDANT_DAWN -> verdantDawn
        else -> embermire
    }
}

/** 默认色板（余烬）的完整深色角色，照搬 ZL2 数值 */
private val embermireDark = darkColorScheme(
    primary = Color(0xFFFFB59F),
    onPrimary = Color(0xFF5F1600),
    primaryContainer = Color(0xFFC4502B),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFB59F),
    onSecondary = Color(0xFF512214),
    secondaryContainer = Color(0xFF643122),
    onSecondaryContainer = Color(0xFFFFC8B9),
    tertiary = Color(0xFFF0D44C),
    onTertiary = Color(0xFF393000),
    tertiaryContainer = Color(0xFFC4AA22),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C110E),
    onBackground = Color(0xFFF4DED8),
    surface = Color(0xFF1C110E),
    onSurface = Color(0xFFF4DED8),
    surfaceVariant = Color(0xFF58423B),
    onSurfaceVariant = Color(0xFFDFC0B7),
    outline = Color(0xFFA68B83),
    outlineVariant = Color(0xFF58423B),
    surfaceBright = Color(0xFF443632),
    surfaceDim = Color(0xFF1C110E),
    inverseSurface = Color(0xFFF4DED8),
    inverseOnSurface = Color(0xFF3B2D2A),
    inversePrimary = Color(0xFFA63A17),
    surfaceContainerLowest = Color(0xFF160C09),
    surfaceContainerLow = Color(0xFF241916),
    surfaceContainer = Color(0xFF291D19),
    surfaceContainerHigh = Color(0xFF342723),
    surfaceContainerHighest = Color(0xFF3F322E)
)

private val embermireLight = lightColorScheme(
    primary = Color(0xFFA63A17),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFE7A52),
    onPrimaryContainer = Color(0xFF2F0700),
    secondary = Color(0xFF894F3D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFBBA7),
    tertiary = Color(0xFF6E5E00),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF241916),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF241916),
    surfaceVariant = Color(0xFFFCDBD3),
    onSurfaceVariant = Color(0xFF58423B),
    outline = Color(0xFF8B716A),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1ED),
    surfaceContainer = Color(0xFFFFE9E4),
    surfaceContainerHigh = Color(0xFFFAE3DD),
    surfaceContainerHighest = Color(0xFFF4DED8)
)

/** ZL2 风格的排版：标题偏紧凑、正文行高略松，配合卡片式布局 */
private val ZTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
)

/** 卡片圆角比 Material3 默认更大，贴近 ZL2 的卡片视觉 */
private val ZShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ZTheme(
    themeType: ZThemeType = ZThemeType.EMBERMIRE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = when (themeType) {
        ZThemeType.EMBERMIRE, ZThemeType.DYNAMIC, ZThemeType.CUSTOM ->
            if (darkTheme) embermireDark else embermireLight

        else -> {
            val a = ZPalettes.anchorOf(themeType)
            if (darkTheme) {
                darkColorScheme(primary = a.dark, primaryContainer = a.containerDark)
            } else {
                lightColorScheme(primary = a.light, primaryContainer = a.containerLight)
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ZTypography,
        shapes = ZShapes,
        content = content
    )
}

/** 把持久化的枚举名安全解析回 [ZThemeType]，非法值回落到默认色板 */
fun zThemeTypeOf(name: String?): ZThemeType =
    ZThemeType.entries.firstOrNull { it.name == name } ?: ZThemeType.EMBERMIRE

/**
 * 全局主题状态。
 *
 * 个性化页改主题后，MainActivity 里的 `ZTheme` 需要立即重组，
 * 所以主题名放在 Compose 的 snapshot state 里，而不是只写 SharedPreferences。
 */
object ZAppTheme {
    var current: String by mutableStateOf(Prefs.themeType)

    fun set(type: ZThemeType) {
        current = type.name
        Prefs.themeType = type.name
    }
}

/** 与 ZL2 Palette.kt 一致的布局语义 */
object ZPalette {
    val appBackground: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val cardBackground: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceBright
    val onCard: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface
    val itemBackground: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant
}
