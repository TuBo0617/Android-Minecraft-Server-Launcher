package com.tubo.mcserver.ui.shell

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.tubo.mcserver.InstancesFragment
import com.tubo.mcserver.ModsFragment
import com.tubo.mcserver.R
import com.tubo.mcserver.SettingsFragment

/**
 * 过渡期的旧页面桥接。
 *
 * Compose 外壳只负责导航与布局，尚未迁移的页面仍然是 Fragment；
 * 这里用 [FragmentContainerView] + [AndroidView] 把 Fragment 挂进 Compose 树。
 *
 * 每个 route 一个 `key`，切换分类时容器重建、事务重新提交，
 * 行为与旧版 `MainActivity.selectTab`（每次 replace）保持一致。
 */
@Composable
fun LegacyScreen(route: AppRoute, modifier: Modifier = Modifier) {
    key(route) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                FragmentContainerView(ctx).apply {
                    id = R.id.legacy_fragment_host
                    // 等容器真正挂到窗口上，FragmentManager 才能按 id 找到它
                    post {
                        if (!isAttachedToWindow) return@post
                        val activity = ctx.findFragmentActivity() ?: return@post
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        activity.supportFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .replace(R.id.legacy_fragment_host, createFragment(route), tagOf(route))
                            .commitAllowingStateLoss()
                    }
                }
            }
        )
    }
}

private fun tagOf(route: AppRoute): String = "legacy_${route.name}"

private fun createFragment(route: AppRoute): Fragment = when (route) {
    AppRoute.RESOURCES -> ModsFragment()
    else -> Fragment()
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
