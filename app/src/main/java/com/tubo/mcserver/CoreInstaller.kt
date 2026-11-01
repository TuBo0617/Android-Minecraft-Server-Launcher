package com.tubo.mcserver

import android.os.Handler
import android.os.Looper

/**
 * 把「安装服务端核心」统一成一处入口，新建实例页、实例设置页、实例列表共用。
 *
 * 下载任务会登记到「下载中心」，同时把结果回调给调用方（切回主线程），
 * 方便界面直接弹窗提示失败原因——之前只在下载中心可见，用户容易以为「点了没反应」。
 */
object CoreInstaller {

    private val main = Handler(Looper.getMainLooper())

    fun start(
        inst: Instance,
        coreId: String = inst.core.ifEmpty { inst.loader },
        onFinish: ((Boolean, String) -> Unit)? = null
    ) {
        val option = ServerCoreCatalog.find(coreId)
        val isBedrock = inst.isBedrock() || coreId == "BDS"
        val label = if (isBedrock) "基岩版 BDS" else option?.name ?: coreId
        val version = if (isBedrock) BedrockManager.latest()?.version ?: "" else inst.mcVersion
        val task = DlManager.create("核心 $label $version".trim(), "server")

        DlManager.submit(task) { t ->
            // 基岩版走独立链路：下载官方 BDS zip 并解压，不经过 Java 核心的下载器
            if (isBedrock) {
                val (ok, msg) = BedrockManager.downloadBds(
                    inst,
                    onProgress = { done, total -> DlManager.progress(t, done, total) },
                    onLog = { line -> t.log(line) },
                    shouldCancel = { t.cancelRequested }
                )
                if (ok) DlManager.success(t, msg)
                else if (t.cancelRequested) DlManager.cancelled(t)
                else DlManager.fail(t, msg)
                runCatching { onFinish?.invoke(ok, msg) }
                return@submit
            }

            val result = try {
                CoreDownloader.download(
                    inst,
                    coreId,
                    { done, total -> DlManager.progress(t, done, total) },
                    { line -> t.log(line) },
                    { t.cancelRequested }
                )
            } catch (e: Exception) {
                Pair(false, "异常: ${e.message}")
            }

            val (ok, msg) = result
            when {
                ok -> DlManager.success(t, msg)
                t.cancelRequested -> DlManager.cancelled(t)
                else -> DlManager.fail(t, msg)
            }
            if (onFinish != null) {
                main.post { runCatching { onFinish(ok, msg) } }
            }
        }
    }
}
