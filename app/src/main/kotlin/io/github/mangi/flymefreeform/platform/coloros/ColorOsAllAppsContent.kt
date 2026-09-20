package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.os.Build
import android.os.IInterface
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 仅适配已核对的 ColorOS 16.14.6 与 ColorOS 17.9.2；独立创建内容视图，不调用侧栏展开、点击全部或修改原生面板状态。 */
internal class ColorOsAllAppsContent(loader: ClassLoader) {
    private val allClass = loader.loadClass(ALL_CLASS)
    private val searchClass = loader.loadClass(SEARCH_CLASS)
    private val mainClass = loader.loadClass(MAIN_CLASS)
    private val userClass = loader.loadClass(USER_CLASS)
    private val cardClass = loader.loadClass("com.coui.appcompat.cardview.COUICardView")
    private val handlerClass = loader.loadClass(HANDLER_CLASS)
    private val handler = handlerClass.getField("INSTANCE").get(null)
    private val getMain = handlerClass.getMethod("getMPanelMainView")
    private val getResident = handlerClass.getMethod("getMResidentProcessHandler")
    private val getState = mainClass.getMethod("getMState")
    private val getUser = mainClass.getMethod("getMPanel")
    private val getLeft = mainClass.getMethod("getMIsLeft")
    private val getRadius = cardClass.getMethod("getRadius")
    private val getWeight = cardClass.getMethod("getWeight")
    private val getColor = userClass.getMethod("getUserPanelBgColor")
    private val getSearch = allClass.getMethod("getSearchHelper")
    private val getRouter = searchClass.getMethod("getAppDataHandler")
    private val load = searchClass.getMethod("loadData")
    private val stop = searchClass.getMethod("stopLoadData")
    private val getSearchState = searchClass.getMethod("getSearchState")
    private val getData = searchClass.getMethod("getCurrentDataList")
    private val dataClass = loader.loadClass(DATA_CLASS)
    // ColorOS 17.9.2 起 getKey 由父类 TitleLabelData 提供，getMethod 仍可解析到。
    private val getKey = dataClass.getMethod("getKey")
    private val getEntry = dataClass.getMethod("getEntryBean")
    private val getType = getEntry.returnType.getMethod("getType")
    private val adapterClass = loader.loadClass(ADAPTER_CLASS)
    private val getClick = adapterClass.getMethod("getOnItemClick")
    private val setClick = adapterClass.getMethod("setOnItemClick", getClick.returnType)
    private val invokeClick = getClick.returnType.getMethod("invoke", Any::class.java, Any::class.java)
    private val nativeUnit = Unit
    private val cancelSearch = allClass.getMethod("cancelSearchEditing", Boolean::class.javaPrimitiveType)
    private val gap = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper").getMethod("getPanelGap")

    // Service 使用平台默认主题；原厂内容依赖 App 的配置/主题包装，不能直接传 Service。
    private val context = themedContext(loader)
    private val colorOs17 = Build.VERSION.SDK_INT >= 37

    // ColorOS 16 的这些类仍保留旧版混淆接口；ColorOS 17 已改为独立的 BlurUtil/SidebarPlatformBlurHelper，
    // 本版本不再强行调用旧混淆方法，避免“全部”面板因视觉辅助类漂移而整体初始化失败。
    private val resourceClass = if (colorOs17) null else loader.loadClass("com.oplus.smartsidebar.utils.c0")
    private val resource = resourceClass?.getField("a")?.get(null)
    private val isLandscape = resource?.javaClass?.getMethod("k")
    private val isPortrait = resource?.javaClass?.getMethod("s")
    private val isTabletop = resource?.javaClass?.getMethod("n", Boolean::class.javaPrimitiveType)
    private val blurClass = if (colorOs17) null else loader.loadClass("com.oplus.smartsidebar.utils.b")
    private val supportsBlur = blurClass?.getMethod("k")
    private val makeBlur = blurClass?.getMethod("q", View::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val updateBlur = blurClass?.let { clazz ->
        clazz.getMethod("s", View::class.java, makeBlur!!.returnType, Float::class.javaPrimitiveType)
    }
    private val shadowClass = if (colorOs17) null else loader.loadClass("com.oplus.smartsidebar.utils.g0")
    private val setupShadow = shadowClass?.getMethod("d", View::class.java)
    private val updateShadow = shadowClass?.getMethod("e", View::class.java, Float::class.javaPrimitiveType)

    // ColorOS 17 原生「全部」面板的玻璃不是窗口模糊，而是 SmartSideBar 自带的
    // SidebarPlatformBlurHelper：解析视图所在窗口的 SurfaceControl，创建 posteffect 背景模糊
    // Drawable 作为视图背景，并叠加 luminosity/dodge 两层 AGSL 混合色与材质光照。
    // 我们与原生同进程同类加载器，直接反射复用即可得到 1:1 观感；任何一步失败都回退窗口模糊。
    private val glassHelperClass =
        if (colorOs17) {
            runCatching { loader.loadClass(GLASS_HELPER_CLASS) }.getOrNull()
        } else {
            null
        }
    private val glassApply = glassHelperClass?.getMethod(
        "r",
        View::class.java,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    )
    private val glassRelease = glassHelperClass?.getMethod("H")
    private val glassAlpha = glassHelperClass?.getMethod("S", Float::class.javaPrimitiveType)
    private var glassHelper: Any? = null
    private var glassTried = false

    /** 平台背景模糊是否已附着成功；成功时调用方应关闭窗口级模糊避免双重模糊。 */
    var platformGlass = false
        private set

    val view = construct(allClass) as ViewGroup
    private val search = getSearch.call(view)!!
    val router = getRouter.call(search)!!
    private var closed = false
    private var loading = false
    private var blur: Any? = null
    private var blurView: View? = null
    private var blurPrepared = false
    private var card: FrameLayout? = null

    init {
        require(ViewGroup::class.java.isAssignableFrom(allClass))
        require(FrameLayout::class.java.isAssignableFrom(cardClass))
        require(getRouter.returnType.name == ROUTER_CLASS)
        allClass.getMethod("showAllPanel", Boolean::class.javaPrimitiveType).call(view, leftSide())
    }

    fun ready(): Boolean = view.childCount > 0 && getMain.call(handler) != null &&
        (getResident.call(handler) as? IInterface)?.asBinder()?.isBinderAlive == true

    fun sidebarHidden(): Boolean = (getMain.call(handler)?.let { getState.call(it) } as? Enum<*>)?.name == "FLOAT_BAR_SHOWING"

    fun leftSide(): Boolean = getMain.call(handler)?.let { getLeft.call(it) as Boolean } ?: false

    fun startLoading() {
        if (closed || loading) return
        loading = true
        load.call(search)
    }

    fun refresh() {
        if (closed || !loading) return
        stop.call(search)
        load.call(search)
    }

    fun bindClose(action: () -> Unit) {
        if (closed) return
        view.findViewById<View>(resourceId("close", "id"))?.setOnClickListener { action() }
    }

    fun bindAdapter(adapter: Any, onClick: (Boolean, () -> Unit) -> Unit) {
        val original = getClick.call(adapter) ?: return
        val callback = Proxy.newProxyInstance(getClick.returnType.classLoader, arrayOf(getClick.returnType)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    if (!closed) {
                        val index = args?.getOrNull(0) as? Int
                        val clickType = args?.getOrNull(1) as? Int
                        val list = getData.call(search) as? List<*>
                        val data = index?.let { list?.getOrNull(it) }
                        if (data != null && clickType != null && dataClass.isInstance(data)) {
                            val key = getKey.call(data)
                            val entry = getEntry.call(data)
                            val tool = entry != null && getType.call(entry) == 0
                            onClick(tool) {
                                // 动画期间目录可能刷新，执行前按原条目标识重新定位，不能复用旧索引。
                                val current = getData.call(search) as? List<*>
                                val targetIndex = current?.indexOfFirst { it != null && dataClass.isInstance(it) && getKey.call(it) == key } ?: -1
                                if (!closed && targetIndex >= 0) invokeClick.call(original, targetIndex, clickType)
                            }
                        }
                    }
                    nativeUnit
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "AllAppsClickCallback"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        setClick.call(adapter, callback)
    }

    fun leaveSearch(): Boolean {
        if (getSearchState.call(search) != true) return false
        cancelSearch.call(view, true)
        return true
    }

    fun createCard(): FrameLayout {
        val original = getMain.call(handler)?.let { getUser.call(it) }
            ?: throw IllegalStateException("SIDEBAR_STYLE_NOT_READY")
        val surface = construct(cardClass) as FrameLayout
        cardClass.getMethod("setRadius", Float::class.javaPrimitiveType).call(surface, getRadius.call(original))
        cardClass.getMethod("setWeight", Float::class.javaPrimitiveType).call(surface, getWeight.call(original))
        cardClass.getMethod("setPreventCornerOverlap", Boolean::class.javaPrimitiveType).call(surface, false)
        cardClass.getMethod("setUseCompatPadding", Boolean::class.javaPrimitiveType).call(surface, false)
        // ColorOS 17 用平台窗口模糊还原玻璃质感：底色不能保留不透明原厂色（会把窗口模糊完全挡住）。
        // 原生「全部」面板的表面色是厂商资源 coui_popup_list_blend_blur_light（#BFEEEEEE，
        // 浅灰 + alpha 191），比纯白更透且带磨砂感；优先从 SmartSideBar 资源实时解析
        // （可随夜间模式取对应变体），解析失败回退到设备上抠出的同值硬编码。
        val panelColor = getColor.call(original) as Int
        val surfaceColor =
            if (colorOs17) {
                val base = glassSurfaceColor(panelColor)
                val alpha =
                    runCatching {
                        Settings.Global.getInt(
                            context.contentResolver,
                            SURFACE_ALPHA_KEY,
                            base ushr 24,
                        )
                    }.getOrDefault(base ushr 24)
                (base and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
            } else {
                panelColor
            }
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, surfaceColor)
        surface.clipToOutline = true
        surface.isForceDarkAllowed = false
        if (!colorOs17) {
            setupShadow?.call(null, surface)
            updateShadow?.call(null, surface, 1f)
        } else {
            // ColorOS 17 的旧 g0 阴影辅助类已不再是工具类；使用 CardView 自带 elevation 作为安全降级。
            surface.elevation = 1f
        }
        surface.addView(view, FrameLayout.LayoutParams(-1, -1))
        card = surface
        return surface
    }

    /** 新增背景 View 后必须先完成一次布局，再让厂商模糊管理器读取其窗口坐标。 */
    fun attachBlur(): Boolean {
        if (colorOs17) {
            attachPlatformGlass()
            return true
        }
        val surface = card ?: return false
        if (blur != null) return true
        if (!blurPrepared) {
            blurPrepared = true
            if (supportsBlur?.call(null) != true) return true
            blurView = View(context).also { surface.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
            return false
        }
        val background = blurView ?: return true
        if (!background.isLaidOut || background.isLayoutRequested ||
            background.width != surface.width || background.height != surface.height
        ) return false
        val manager = makeBlur?.call(null, background, false, false)
        if (manager == null) {
            surface.removeView(background)
            blurView = null
            return true
        }
        background.background?.setBounds(0, 0, background.width, background.height)
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, 0)
        blur = manager
        blurView = background
        updateBlur(1f)
        return true
    }

    /**
     * 尝试用原生 SidebarPlatformBlurHelper 给卡片附着 posteffect 背景模糊。
     * 只尝试一次：类/方法缺失、系统不支持（r 返回 false）或抛错都保持窗口模糊回退方案。
     * 可用 `adb shell settings put global flymefreeform_platform_blur 0` 强制走回退方案。
     */
    private fun attachPlatformGlass() {
        if (platformGlass || glassTried) return
        glassTried = true
        val type = glassHelperClass ?: return
        val apply = glassApply ?: return
        val surface = card ?: return
        val enabled =
            runCatching {
                Settings.Global.getInt(context.contentResolver, PLATFORM_GLASS_KEY, 1)
            }.getOrDefault(1)
        if (enabled == 0) return
        runCatching {
            val helper = type.getConstructor().newInstance()
            // r(view, asBackground=true, withMaterial=true)：与原生面板一致的附着方式。
            if (apply.invoke(helper, surface, true, true) == true) {
                glassHelper = helper
                platformGlass = true
                // 模糊 Drawable 即视图背景，卡片底色必须透明，否则挡住玻璃。
                cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, 0)
                surface.elevation = 0f
            }
        }
    }

    fun updateBlur(alpha: Float) {
        if (colorOs17) {
            glassHelper?.let { helper ->
                runCatching { glassAlpha?.invoke(helper, alpha.coerceIn(0f, 1f)) }
            } ?: run { card?.elevation = alpha }
            return
        }
        blurView?.let { updateBlur?.call(null, it, blur, alpha) }
        card?.let { updateShadow?.call(null, it, alpha) }
    }

    fun dimensions() = AllAppsPanelGeometry.Dimensions(
        dimension("all_app_panel_max_width"), dimension("all_app_panel_max_height"),
        dimension("all_app_panel_height_small_portrait"), dimension("all_app_panel_margin_min"),
        dimension("all_app_panel_margin_max"), gap.call(null) as Int,
    )

    fun mode(): AllAppsPanelGeometry.Mode {
        if (colorOs17) {
            return when (context.resources.configuration.orientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> AllAppsPanelGeometry.Mode.Landscape
                android.content.res.Configuration.ORIENTATION_PORTRAIT ->
                    if (isLargePortrait()) {
                        AllAppsPanelGeometry.Mode.LargePortrait
                    } else {
                        AllAppsPanelGeometry.Mode.Portrait
                    }
                else -> AllAppsPanelGeometry.Mode.LargePortrait
            }
        }
        return when {
            isTabletop?.call(resource, false) == true -> AllAppsPanelGeometry.Mode.Tabletop
            isLandscape?.call(resource) == true -> AllAppsPanelGeometry.Mode.Landscape
            isPortrait?.call(resource) == true -> AllAppsPanelGeometry.Mode.Portrait
            else -> AllAppsPanelGeometry.Mode.LargePortrait
        }
    }

    /**
     * ColorOS 17 起厂商的资源辅助类已被混淆替换，无法再询问“是否大屏竖屏”。
     * 原厂按屏幕尺寸区分：`all_app_panel_height_small_portrait`（578dp，居中短面板）用于手机，
     * 大屏才铺满 `all_app_panel_max_height`（640dp）。因此按标准的大屏阈值判断，
     * 普通手机走 Portrait，避免面板被误判成大屏而明显偏大。
     */
    private fun isLargePortrait(): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_WIDTH_DP

    /**
     * 解析 ColorOS 17 原生「全部」面板的玻璃表面色。
     * 优先取 SmartSideBar 资源 `coui_popup_list_blend_blur_light`（随系统夜间模式取对应变体）；
     * 资源缺失时回退到从设备 SmartSideBar.apk 资源表抠出的实测值 #BFEEEEEE。
     * 再兜底保留原厂色相，仅沿用传入色。
     */
    private fun glassSurfaceColor(fallback: Int): Int {
        val id = runCatching { resourceId(GLASS_SURFACE_COLOR_RES, "color") }.getOrDefault(0)
        if (id != 0) {
            runCatching { return context.resources.getColor(id, context.theme) }
        }
        return if (id != 0) fallback else GLASS_SURFACE_COLOR
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            // ColorOS 17 已移除旧的搜索动画访问器；关闭时只需停止数据加载即可。
        } finally {
            try {
                if (loading) stop.call(search)
            } finally {
                try {
                    glassHelper?.let { helper -> runCatching { glassRelease?.invoke(helper) } }
                    glassHelper = null
                } finally {
                    loading = false
                    blurView?.background = null
                    blurView = null
                    blur = null
                    card = null
                }
            }
        }
    }

    private fun dimension(name: String): Int = context.resources.getDimensionPixelSize(resourceId(name, "dimen"))

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME).also { require(it != 0) }

    private fun Method.call(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    private fun construct(type: Class<*>): Any = try {
        type.getConstructor(Context::class.java).newInstance(context)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    @Suppress("DiscouragedApi")
    private fun themedContext(loader: ClassLoader): Context {
        val native = (getMain.call(handler) as? View)?.context
            ?: loader.loadClass("com.coloros.common.App").getField("sContext").get(null) as? Context
            ?: throw IllegalStateException("ALL_APPS_THEME_CONTEXT_UNAVAILABLE")
        for (name in listOf("couiColorPrimaryTextOnPopup", "couiColorPrimaryNeutral", "couiColorSurfaceWithCard")) {
            val id = native.resources.getIdentifier(name, "attr", ColorOsSidebarTarget.PACKAGE_NAME)
            val value = TypedValue()
            check(id != 0 && native.theme.resolveAttribute(id, value, true) &&
                value.type != TypedValue.TYPE_ATTRIBUTE && value.type != TypedValue.TYPE_NULL
            ) { "ALL_APPS_THEME_ATTRIBUTE_UNAVAILABLE" }
        }
        return native
    }

    companion object {
        const val ALL_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppPanelView"
        const val ADAPTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppRecyclerAdapter"
        const val ROUTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.AllAppDataHandlerImpl"
        const val DATA_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData"
        const val SEARCH_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.utils.SearchHelper"
        const val HANDLER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl"
        const val MAIN_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView"
        const val USER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView"

        /** ColorOS 17 原生玻璃：posteffect 背景模糊附着辅助类。 */
        const val GLASS_HELPER_CLASS = "com.oplus.smartsidebar.utils.SidebarPlatformBlurHelper"

        /** 覆盖键：`adb shell settings put global flymefreeform_platform_blur 0` 关闭平台模糊。 */
        const val PLATFORM_GLASS_KEY = "flymefreeform_platform_blur"

        /** 原生「全部」面板的玻璃表面色资源名（SmartSideBar 资源表内）。 */
        const val GLASS_SURFACE_COLOR_RES = "coui_popup_list_blend_blur_light"

        /** 资源解析失败时的回退值：设备 SmartSideBar.apk 资源表实测 #BFEEEEEE。 */
        const val GLASS_SURFACE_COLOR = 0xBFEEEEEE.toInt()

        /** Android 标准大屏阈值；低于该值按手机竖屏处理。 */
        const val LARGE_SCREEN_WIDTH_DP = 600

        /** 玻璃面板底色不透明度默认值；越低越透出背后的窗口模糊。 */
        const val GLASS_SURFACE_ALPHA = 0xCC

        /** 覆盖键：`adb shell settings put global flymefreeform_surface_alpha 230` */
        const val SURFACE_ALPHA_KEY = "flymefreeform_surface_alpha"
    }
}
