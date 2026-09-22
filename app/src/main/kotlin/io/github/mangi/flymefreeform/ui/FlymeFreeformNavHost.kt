package io.github.mangi.flymefreeform.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.mangi.flymefreeform.apps.InstalledLauncherApp
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.TriggerShape
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<InstalledLauncherApp>,
    onModuleEnabledChange: (Boolean) -> Unit,
    onLeftCornerEnabledChange: (Boolean) -> Unit,
    onRightCornerEnabledChange: (Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    /** @author bomo 触发热区形状（扇形 / 三角形）变更。 */
    onTriggerShapeChange: (TriggerShape) -> Unit,
    /** @author bomo 三角形热区横向长度变更（沿屏幕底边）。 */
    onTriggerHorizontalDpChange: (Int) -> Unit,
    /** @author bomo 三角形热区纵向高度变更（沿屏幕侧边）。 */
    onTriggerVerticalDpChange: (Int) -> Unit,
    /** @author bomo 「全部」面板缩放百分比变更。 */
    onPanelScaleChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedComponentsChange: (List<ComponentName>) -> Unit,
    onInnerPinnedComponentsChange: (List<ComponentName>) -> Unit,
) {
    val backStack = rememberNavBackStack(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
        entryProvider =
            entryProvider {
                entry<AppRoute.Settings> {
                    ControlScreen(
                        state = state,
                        onModuleEnabledChange = onModuleEnabledChange,
                        onLeftCornerEnabledChange = onLeftCornerEnabledChange,
                        onRightCornerEnabledChange = onRightCornerEnabledChange,
                        onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                        onTriggerShapeChange = onTriggerShapeChange,
                        onTriggerHorizontalDpChange = onTriggerHorizontalDpChange,
                        onTriggerVerticalDpChange = onTriggerVerticalDpChange,
                        onPanelScaleChange = onPanelScaleChange,
                        onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                        onHandleSwipeUpToMiniEnabledChange =
                            onHandleSwipeUpToMiniEnabledChange,
                        onPauseInLandscapeChange = onPauseInLandscapeChange,
                        onPauseInGameModeChange = onPauseInGameModeChange,
                        onRequestScopes = onRequestScopes,
                        onNavigateToPinnedApps = navigateToPinnedApps,
                    )
                }
                entry<AppRoute.PinnedApps> {
                    PinnedAppsScreen(
                        state = state,
                        apps = apps,
                        onBack = popBackStack,
                        onPinnedComponentsChange = onPinnedComponentsChange,
                        onInnerPinnedComponentsChange = onInnerPinnedComponentsChange,
                    )
                }
            },
    )
}
