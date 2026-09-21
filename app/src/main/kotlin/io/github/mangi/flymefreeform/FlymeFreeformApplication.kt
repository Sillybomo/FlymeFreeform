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
            LauncherAppRepository(this) { SharedSettings.readToolCatalog(this) ?: frameworkConnectionRepository.readToolCatalog() }
                .also { it.refresh() }
        // 侧边栏工具目录要等框架连上后才可读：连接状态变化时重算工具区（目录未变则走缓存）。
        scope.launch {
            frameworkConnectionRepository.state.collect { launcherAppRepository.refreshTools() }
        }
    }
}
