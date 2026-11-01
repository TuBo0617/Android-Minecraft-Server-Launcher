package com.tubo.mcserver.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 侧边栏一级分类。
 *
 * `section` 用于侧边栏分组展示；`native` 表示该页是否已经迁移为 Compose 原生页，
 * 未迁移的暂时由旧 Fragment 桥接承载（见 [LegacyScreen]）。
 *
 * 注意：服务端核心选择不再是独立页面，已并入「实例设置 → 服务端核心」。
 */
enum class AppRoute(
    val label: String,
    val icon: ImageVector,
    val section: String,
    val native: Boolean = false
) {
    INSTANCES("实例", Icons.Outlined.Home, "主页", native = true),
    RESOURCES("资源中心", Icons.Outlined.Extension, "资源"),
    CONSOLE("控制台", Icons.Outlined.PlayArrow, "运行"),
    MULTIPLAYER("联机", Icons.Outlined.Groups, "运行"),
    DOWNLOADS("下载中心", Icons.Outlined.Download, "运行"),
    SETTINGS("设置", Icons.Outlined.Settings, "系统", native = true)
}
