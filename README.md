<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_mark.png" width="112" alt="Flyme 小窗图标" />
</p>

<h1 align="center">Flyme 小窗（FlymeFreeform）</h1>

<p align="center">
  <img src="https://img.shields.io/badge/ROM-ColorOS-00A862" alt="面向 ColorOS" />
  <img src="https://img.shields.io/badge/minSdk-35-3DDC84?logo=android" alt="最低 API 35" />
  <img src="https://img.shields.io/badge/libxposed-API_102-4285F4" alt="libxposed API 102" />
</p>

> **本仓库是二次开发版（衍生版本），非原作者发布。**
>
> 基于 [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform) 修改，
> 主要针对 **ColorOS 17 / Android 17（SDK 37）** 做适配与修复，同时修复若干已知问题。
> 原项目版权归原作者 **Mangi-11** 所有；本项目作为衍生作品，同样以 **GPL-3.0** 授权。
> 想了解原始版本请前往上游仓库；本衍生版的具体改动见下方[「本衍生版的改动」](#本衍生版的改动)。

魅族 Flyme 的小窗，将“呼之即来，挥之即去”做得轻巧又顺手。几次简单的滑动与轻点，便能处理眼前的小事，这份细腻的巧思让人愉悦。

本项目通过 Xposed 模块，将这份即用即走的快捷交互带到 ColorOS。

## 预览

<p align="center">
  <img src="docs/images/preview.gif" width="360" alt="Flyme 小窗交互预览" />
</p>

## 功能

- **快速唤出**：从左右下角斜向内上滑，呼出扇形菜单，滑选应用后松手以小窗打开。
- **窗外关闭**：支持单击或双击小窗外关闭，也可关闭此功能。
- **上滑迷你窗**：快速上滑普通小窗底部横条，切换为迷你小窗。
- **应用管理**：最多固定六个应用，支持拖动排序，通过“更多”访问其他应用与系统工具。
- **手势设置**：左右入口独立开关，可调整角落触发范围。
- **自动暂停**：默认在横屏或游戏模式下暂停增强，退出后自动恢复，可分别关闭。

*更多功能持续开发中。*

## 本衍生版的改动

基于上游 `dbbf0d5`（2026-09-10），修改日期 **2026-09-20**，修改者 **bomo**。
所有厂商接口均从设备上的实际 APK / JAR 核对取得，未凭推测；**ColorOS 16 的适配路径保持原样**。

### 适配

- **ColorOS 17 / Android 17（SDK 37）支持**：重新定位四类已失效的系统 Hook ——
  角落输入入口（`OplusBaseTouchInteractionService`）、SystemUI 应用类
  （`application.impl.SystemUIApplicationImpl`）、输入法窗口字段
  （`DisplayContent.mImeWindow`）、指针抢占通道（`InputMonitorCompat` 已被移除）；
  智能侧边栏版本白名单扩充至 `17.9.2 / 170009002`。
  在此之前，模块在 ColorOS 17 上部分 Hook 装载失败，扇形菜单与「全部」面板均不可用。
- **侧边栏版本判断分级化**：「全部」面板不再要求侧边栏版本号完全一致，
  小版本更新（如 `17.9.2` → `17.9.3`）后仍可继续使用；
  真正不兼容时会在反射阶段安全降级并打印诊断码，不会崩溃。

### 修复

- **扇形菜单图标偏小、方形图标在圆形容器里视觉上变「菱形」**：
  上游对非自适应图标是原样放行的，而 ColorOS 返回的图标位图自身带约 7% 的透明安全区留白，
  放进圆形容器后四周露白。现改为裁掉留白并等比放大填满外框。
- **「全部」面板左上角「关闭」按钮点击无反应**：ColorOS 17 将该按钮的资源 id
  由 `close` 改为 `cancel`，旧 id 查找必然落空，点击被系统原厂逻辑接管。
  现按新 id 重新绑定，面板可正常关闭。

### 优化

- **「全部」面板玻璃观感 1:1 还原原生**：不再使用通用窗口模糊，改为复用系统自带的
  `SidebarPlatformBlurHelper`（posteffect 背景模糊 + AGSL 双层混合 + 材质光照），
  与原厂面板通透度、磨砂感一致；失败时自动回退窗口模糊。
- **扇形几何收紧**：在 7 条目 / 84° 弧 / 槽位间隔 12° 的硬约束下，
  容器半径 −2.5%、图标直径 +8%，把图标尺寸推到上限的 96%（再大需允许图标互叠）。

## 设计与实现

本项目借鉴 Flyme 的快捷交互，复刻扇形菜单的展开动画与滑选体验；“更多”窗口复用 ColorOS 智能侧边栏的“全部”面板，应用窗口继续由系统已有的自由窗能力管理。

完整照搬另一套系统的界面，容易造成视觉与操作上的割裂；另建一套小窗能力，也会增加系统适配与后续维护的成本。因此，我们保留 Flyme 的交互巧思，同时沿用 ColorOS 的界面与窗口能力，让这份体验自然融入当前系统。

## 致谢

- [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform)：本项目所基于的原项目。感谢原作者的开源与设计。
- [Flyme](https://www.flyme.com/)：感谢其小窗细腻的设计与交互巧思。
- [libxposed API](https://github.com/libxposed/api)：现代 Xposed API。
- [Miuix](https://github.com/compose-miuix-ui/miuix)：UI 组件库。

## 许可证

本项目基于 GPL-3.0 开源，详情请参阅 [LICENSE](LICENSE)。

本仓库为 [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform) 的衍生版本，
在原项目基础上进行了修改（修改日期与内容见[「本衍生版的改动」](#本衍生版的改动)）。
原项目版权归原作者所有，本衍生版同样以 GPL-3.0 授权。

> 免责声明：Xposed / LSPosed 模块会修改系统底层行为，存在系统不稳定、应用闪退甚至无法开机的风险。
> 请确保在具备救砖能力的前提下使用。因使用本模块导致的一切后果，本人及原作者均不承担责任。
> 本模块完全免费，严禁倒卖或用于任何盈利目的。
