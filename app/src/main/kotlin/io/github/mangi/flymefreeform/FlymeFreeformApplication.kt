package io.github.mangi.flymefreeform

import android.app.Application
import io.github.mangi.flymefreeform.apps.LauncherAppRepository
import io.github.mangi.flymefreeform.config.SharedSettings
import io.github.mangi.flymefreeform.framework.FrameworkConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlymeFreeformApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    /** 只用于观察框架连接状态；与 Application 同为进程级，无需取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository().also { it.start() }
        launcherAppRepository =
            // 远端配置（App 落盘，带 72px WEBP 图标）优先；Settings.Global 只有去图标
            // 文本副本（单值上限 32KB，实测带图标被拒），作框架未连接时的兜底。
            LauncherAppRepository(this) { frameworkConnectionRepository.readToolCatalog() ?: SharedSettings.readToolCatalog(this) }
                .also { it.refresh() }
        // 侧边栏工具目录要等框架连上后才可读：连接状态变化时重算工具区（目录未变则走缓存）。
        scope.launch {
            frameworkConnectionRepository.state.collect { launcherAppRepository.refreshTools() }
        }
    }
}
