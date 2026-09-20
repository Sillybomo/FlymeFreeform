package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 仅启用已核对的系统包；包名相同或方法名相似不足以证明兼容。
 *
 * @author bomo 版本判断分级化：精确版本（16.14.6 / 17.9.2）作为快速通道；
 * 未精确命中但 SDK 不低于 [MIN_VERIFIED_SDK] 时仍放行，改由结构校验
 * （服务、Provider、权限、进程名、system flag）与运行期诊断码兜底。
 * 这样侧边栏小版本更新不会让功能整体失效，而真正的不兼容仍会安全降级。
 */
internal object ColorOsSidebarTarget {
    const val PACKAGE_NAME = "com.coloros.smartsidebar"
    const val PROCESS_NAME = "$PACKAGE_NAME:ui"
    const val SERVICE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.UIService"
    const val BIND_ACTION = "io.github.mangi.flymefreeform.action.BIND_ALL_APPS_V1"
    const val REQUIRED_PERMISSION = "oppo.permission.OPPO_COMPONENT_SAFE"
    const val PROVIDER_AUTHORITY = "com.coloros.sidebar"
    const val PREPARE_METHOD = "bindServiceForTransferDock"

    /**
     * @author bomo 最低已验证的 SDK 版本（Android 16 = 36）。
     * 精确版本未命中时，只要不低于该基线仍继续尝试：
     * 侧边栏小版本更新（如 17.9.2 → 17.9.3）不应导致功能整体失效；
     * 真正的不兼容会由结构校验与运行期诊断码暴露并安全降级。
     */
    const val MIN_VERIFIED_SDK = 36

    fun supportedUid(context: Context): Int? {
        val manager = context.packageManager
        return try {
            val info = manager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            // @author bomo：分级版本判断。智能侧边栏更新频率高，精确版本号一旦漂移，
            // 旧实现会直接放弃（返回 null）导致「全部」面板整体不可用。
            // 这里保留"已核对版本"的精确匹配作为快速通道；未精确匹配时不直接放弃，
            // 只要 SDK 不低于已验证基线就继续向下走 —— 后文的结构校验（服务/Provider/
            // 权限/进程名/system flag）与运行期诊断码会兜底，失败即安全降级，不崩溃。
            val verified = when {
                Build.VERSION.SDK_INT == 36 && info.longVersionCode == 160014006L && info.versionName == "16.14.6" -> true
                Build.VERSION.SDK_INT == 37 && info.longVersionCode == 170009002L && info.versionName == "17.9.2" -> true
                else -> false
            }
            if (!verified && Build.VERSION.SDK_INT < MIN_VERIFIED_SDK) return null
            val service =
                manager.getServiceInfo(
                    ComponentName(PACKAGE_NAME, SERVICE_CLASS),
                    PackageManager.ComponentInfoFlags.of(0),
                )
            val app = service.applicationInfo
            val provider = manager.resolveContentProvider(PROVIDER_AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
                ?: return null
            if (provider.packageName != PACKAGE_NAME ||
                provider.name != "com.coloros.edgepanel.api.SideBarProvider" ||
                provider.applicationInfo.uid != app.uid || !provider.enabled || !provider.exported ||
                provider.readPermission != "com.coloros.sidebar.permission.data" ||
                provider.writePermission != "com.coloros.sidebar.permission.data"
            ) return null
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (!app.enabled || !service.enabled || !service.exported ||
                app.flags and systemFlags == 0 ||
                app.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
                service.permission != REQUIRED_PERMISSION || service.processName != PROCESS_NAME
            ) {
                null
            } else {
                app.uid
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
