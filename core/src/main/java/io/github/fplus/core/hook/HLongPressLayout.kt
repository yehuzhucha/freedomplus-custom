package io.github.fplus.core.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.freegang.extension.asOrNull
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import com.freegang.extension.findFieldGetValue
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.screenSize
import com.freegang.ktutils.other.KAutomationUtils
import com.freegang.ktutils.view.KViewUtils
import com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.helper.ModuleSettingHelper
import io.github.fplus.core.hook.logic.DownloadLogic
import io.github.fplus.core.hook.logic.cityInfo
import io.github.fplus.core.hook.logic.preciseLocation
import io.github.fplus.core.hook.logic.poiDebug
import io.github.fplus.core.hook.logic.authorCityFast
import io.github.fplus.core.hook.logic.ipFast
import io.github.fplus.core.hook.logic.publishPlaceFast
import io.github.fplus.core.hook.logic.createDate
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisView


class HLongPressLayout : BaseHook() {
    companion object {
        /**
         * 用类名比较替代代理类的 `is`/`as`。
         * aweme 的代理类是 compileOnly（不打进 APK），运行时类由抖音提供；
         * 若该类在目标版本不存在，`is`/`as` 会抛 NoClassDefFoundError。
         * 且这些代码多跑在主线程 post 回调里，无人捕获，会直接打崩抖音。
         */
        private const val PENETRATE_TOUCH_RELATIVE_LAYOUT =
            "com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout"

        /** 36.8.0：当前视频。onBindSafe 里刷新，跨 hook 实例共享（静态，避免各实例各存一份） */
        @Volatile
        var currentAweme: Aweme? = null

        /** 视图 → 该视图绑定的 aweme。预加载下一条时会先绑定，写全局变量就会被覆盖；
         *  按视图存，长按哪个布局就取哪个，物理上不会错位。 */
        val boundByView: MutableMap<View, Aweme> = java.util.WeakHashMap()

        /** presenter 实例 → 它当前绑定的 aweme。presenter 是每个播放页一个，
         *  长按时顺着视图找到它自己那个 presenter，就不会拿到预加载的下一条。 */
        val boundByPresenter: MutableMap<Any, Aweme> = java.util.WeakHashMap()

        /** 最近绑定过的 aweme（预加载会连续绑定多条，正确的往往就在里面） */
        val recentAwemes: MutableList<Aweme> = java.util.Collections.synchronizedList(ArrayList())

        fun rememberAweme(a: Aweme) {
            synchronized(recentAwemes) {
                if (recentAwemes.none { it.aid == a.aid }) {
                    recentAwemes.add(a)
                    if (recentAwemes.size > 6) recentAwemes.removeAt(0)
                }
            }
        }

        /** 长按那一刻抓到的 aweme（带时间戳，避免上一次的残留被复用） */
        @Volatile
        var pendingAweme: Aweme? = null

        @Volatile
        var pendingAt: Long = 0L

        /** 本次长按实际命中的取值来源：tag / view / presenter / global */
        @Volatile
        var lastSource: String = "none"

        /** 最近一次创建的 BaseListFragmentPanel（每个播放页一个），用来调它的 getCurrentAweme() */
        @JvmStatic
        var lastPanel: Any? = null

        /** 所有 BaseListFragmentPanel 实例（每个播放页一个）。Doula 就是靠这个挑"当前可见"的那页。 */
        val feedPanels: MutableList<Any> = java.util.Collections.synchronizedList(ArrayList())

        /** 预先解析好的 getCurrentAweme() 方法，避免运行时再按名字反射（R8 可能把字符串优化掉） */
        @JvmStatic
        var getCurrentAwemeMethod: java.lang.reflect.Method? = null
    }
    private val config
        get() = ConfigV1.get()

    private var lastTouchDown: Long = 0
    private var numberOfTaps: Int = 0
    private var lastTapTimeMs: Long = 0
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f

    private val LONG_PRESS_TIME = ViewConfiguration.getLongPressTimeout()
    private val DOUBLE_TAP_TIME = ViewConfiguration.getDoubleTapTimeout()

    private var longPressRunnable: Runnable? = null

    private var aweme: Aweme? = null

    /** 长按这一刻锁定的 aweme（按视图查出来的），后面弹窗/下载都用它，不再读全局 */
    private var thisAweme: Aweme? = null

    override fun setTargetClass(): Class<*> {
        return findClass("com.ss.android.ugc.aweme.feed.ui.LongPressLayout")
    }

    @OnAfter("onTouchEvent")
    fun onTouchEventAfter(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            longPressRunnable = longPressRunnable ?: Runnable { onLongPress(thisView, event) }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    installPanelHooks(thisView)
                    longPressRunnable?.let { handler.postDelayed(it, LONG_PRESS_TIME.toLong()) }
                    touchDownX = event.x
                    touchDownY = event.y
                    lastTouchDown = System.currentTimeMillis()
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let {
                        handler.removeCallbacks(it)
                        longPressRunnable = null
                    }

                    if (System.currentTimeMillis() - lastTouchDown < LONG_PRESS_TIME) {
                        // onClick(thisView,event)
                        if (System.currentTimeMillis() - lastTapTimeMs < DOUBLE_TAP_TIME) {
                            if (numberOfTaps == 1) {
                                onDoubleClick(thisView, event)
                                numberOfTaps = 0
                            }
                        } else {
                            numberOfTaps = 1
                        }
                        lastTapTimeMs = System.currentTimeMillis()
                    }
                }

                else -> {
                    if (isMoved(event) || event.action == MotionEvent.ACTION_CANCEL) {
                        longPressRunnable?.let {
                            handler.removeCallbacks(it)
                            longPressRunnable = null
                        }
                    }
                }
            }

        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 36.8.0：长按面板不是由 OnLongPressInterceptListener 弹出的，而是由
     * FeedLongPressLayoutPresenter 里另一个 (float,float)->void 回调（X.0Vjd 实现类，
     * 内部 new X.13vb(...) 后调 X.13va.LIZ() 显示）触发的。
     * 该回调是 LongPressLayout 的字段，运行时直接扫字段拿到实例、挂它的方法，
     * 不依赖混淆类名。
     */
    private var panelHookInstalled = false

    private fun installPanelHooks(view: View) {
        if (panelHookInstalled) return
        panelHookInstalled = true
        runCatching {
            view.javaClass.declaredFields.forEach { f ->
                runCatching {
                    f.isAccessible = true
                    val value = f.get(view) ?: return@runCatching
                    val clz = value.javaClass
                    if (clz.isInterface) return@runCatching
                    val hit = clz.declaredMethods.any {
                        it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == java.lang.Float.TYPE &&
                            it.parameterTypes[1] == java.lang.Float.TYPE &&
                            it.returnType == java.lang.Void.TYPE
                    }
                    if (!hit) return@runCatching
                    lparam.hookClass(clz)
                        .methodAllByParamTypes(Float::class.java, Float::class.java) {
                            onBefore {
                                val y = args[1] as Float
                                val half = screenSize.height / 2

                                // 上半屏：长按设置接管
                                if (config.isLongPressSetting && y < half) {
                                    setResultVoid()
                                    return@onBefore
                                }

                                // 清爽模式：只在模块菜单真正会弹出的那一半吃掉原生面板，
                                // 另一半放行给抖音原生菜单（与 onLongPress 的区域判定保持一致）
                                if (config.isNeatMode) {
                                    val menuOnTop = config.longPressMode && !config.isLongPressSetting
                                    val inModuleArea = if (menuOnTop) y < half else y >= half
                                    if (inModuleArea) setResultVoid()
                                }
                            }
                        }
                    XplerLog.i("FPlus", "panelHook installed: ${clz.name}")
                }.onFailure { XplerLog.e(it) }
            }
        }.onFailure { XplerLog.e(it) }
    }

    private fun isMoved(event: MotionEvent): Boolean {
        return Math.abs(touchDownX - event.x) > 10 || Math.abs(touchDownY - event.y) > 10
    }

    private fun onClick(view: View, event: MotionEvent) {

    }

    private fun onDoubleClick(view: View, event: MotionEvent) {
        if (!config.isDoubleClickType)
            return

        val holderRootView = view.firstParentOrNull(VideoViewHolderRootView::class.java) ?: return
        when (config.doubleClickType) {
            1 -> onClickView(holderRootView, targetContent = Regex("评论(.*?)，按钮"))// 打开评论区
            2 -> {} // 点赞
        }
    }

    private fun onLongPress(view: View, event: MotionEvent) {
        // 预留出长按快进
        if (event.x < screenSize.width / 8 || event.x > screenSize.width - screenSize.width / 8)
            return

        // 长按上半部分: 直接唤起模块设置
        if (config.isLongPressSetting && event.y < screenSize.height / 2) {
            ModuleSettingHelper.open(view.context)
            return
        }

        if (!config.isNeatMode)
            return

        // 上半部分被"长按唤起模块设置"占用时, 清爽模式菜单改为只从下半响应,
        // 避免同一个手势触发两个入口。
        val menuOnTop = config.longPressMode && !config.isLongPressSetting
        if (menuOnTop) {
            if (event.y > screenSize.height / 2)
                return
        } else {
            if (event.y < screenSize.height / 2)
                return
        }

        val holderRootView = view.firstParentOrNull(VideoViewHolderRootView::class.java) ?: return
        showOptionsMenu(holderRootView)
    }

    /**
     * 兜底：从视图层级里把 aweme 挖出来。
     * 顺序：祖先 View 的字段 -> 字段里带 ViewHolder 的对象 -> 再下钻一层。
     */
    private fun resolveAwemeFromView(view: View): Aweme? {
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < 10) {
            runCatching {
                cur!!.javaClass.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(cur) ?: return@runCatching
                        if (v is Aweme) return v
                        if (v.javaClass.name.contains("ViewHolder", true)) {
                            v.javaClass.declaredFields.forEach { f2 ->
                                runCatching {
                                    f2.isAccessible = true
                                    val v2 = f2.get(v)
                                    if (v2 is Aweme) return v2
                                }
                            }
                        }
                    }
                }
            }
            val p = cur!!.parent
            cur = if (p is View) p else null
            depth++
        }
        return null
    }

    /**
     * 从长按的那个 View 往上走，找它所属播放页的 presenter，再取该 presenter 绑定的 aweme。
     *
     * 为什么不能直接用全局：预加载下一条时会先调 onBindSafe 写全局，
     * 于是全局里存的是 N+1；只有"划走再划回来"时最后绑定才变回 N。
     */
    private fun awemeOf(o: Any?): Aweme? {
        if (o == null) return null
        (o as? Aweme)?.let { return it }
        var clz: Class<*>? = o.javaClass
        var depth = 0
        while (clz != null && clz != Any::class.java && depth++ < 3) {
            for (f in clz.declaredFields) {
                if (!Aweme::class.java.isAssignableFrom(f.type)) continue
                runCatching { f.isAccessible = true; f.get(o) as? Aweme }.getOrNull()?.let { return it }
            }
            clz = clz.superclass
        }
        return null
    }

    /** 从祖先 ViewPager 的 adapter.getItem(currentItem) 取当前页条目 */
    private fun awemeFromPager(start: View?): Aweme? {
        var v: View? = start
        var guard = 0
        while (v != null && guard++ < 15) {
            val vv: View = v
            if (vv.javaClass.name.contains("ViewPager")) {
                val cur = runCatching { vv.javaClass.getMethod("getCurrentItem").invoke(vv) as Int }.getOrNull()
                val ad = runCatching { vv.javaClass.getMethod("getAdapter").invoke(vv) }.getOrNull()
                if (cur != null && ad != null) {
                    val m = runCatching { ad.javaClass.getMethod("getItem", Int::class.javaPrimitiveType) }.getOrNull()
                    val item = runCatching { m?.invoke(ad, cur) }.getOrNull()
                    awemeOf(item)?.let {
                        lastSource = "pageradapter@" + cur + "/" + runCatching {
                            ad.javaClass.getMethod("getCount").invoke(ad) as Int
                        }.getOrDefault(-1)
                        return it
                    }
                }
            }
            v = v.parent as? View
        }
        return null
    }

    /** 当前可见的 panel —— 按 Doula 的判定：fragment 的 userVisibleHint && isVisible */
    private fun visiblePanel(): Any? {
        val list = synchronized(feedPanels) { feedPanels.toList() }
        for (p in list) {
            val ok = runCatching {
                val f = p.javaClass.getMethod("getFragmentP").invoke(p) ?: return@runCatching false
                val hint = f.javaClass.getMethod("getUserVisibleHint").invoke(f) as? Boolean ?: false
                val vis = f.javaClass.getMethod("isVisible").invoke(f) as? Boolean ?: false
                hint && vis
            }.getOrDefault(false)
            if (ok) return p
        }
        return null
    }

    private fun awemeFromVisiblePanel(): Aweme? {
        val p = visiblePanel() ?: return null
        return runCatching { p.javaClass.getMethod("getCurrentAweme").invoke(p) as? Aweme }.getOrNull()
    }


    /**
     * 反着查：遍历所有登记过的 presenter，看哪个 presenter 持有的 View 是当前视图的祖先。
     * 之前是"从视图往上找 presenter 字段"——方向反了，因为 presenter 持有 view，而不是 view 持有 presenter。
     */
    private fun awemeByPresenterForView(start: View?): Aweme? {
        val chain = ArrayList<View>()
        var v: View? = start
        var g = 0
        while (v != null && g++ < 20) { chain.add(v); v = v.parent as? View }
        if (chain.isEmpty()) return null
        val entries = synchronized(boundByPresenter) { boundByPresenter.entries.toList() }
        for ((p, a) in entries) {
            var clz: Class<*>? = p.javaClass
            var d = 0
            while (clz != null && clz != Any::class.java && d++ < 4) {
                for (f in clz.declaredFields) {
                    if (f.type.isPrimitive) continue
                    val o = runCatching { f.isAccessible = true; f.get(p) }.getOrNull() ?: continue
                    val vv = o as? View ?: continue
                    if (chain.any { it === vv }) { lastSource = "presenter"; return a }
                }
                clz = clz.superclass
            }
        }
        return null
    }

    private fun awemeForThisLayout(start: View?): Aweme? {
        // ① 当前可见的 panel（Doula 做法，最可靠）
        awemeFromVisiblePanel()?.let { lastSource = "visiblepanel"; return it }
        var v: View? = start
        var guard = 0
        while (v != null && guard++ < 8) {
            // ② View 自身字段 / tag 里直接挂着当前条目
            awemeOf(v)?.let { lastSource = "viewfield"; return it }
            awemeOf(v.tag)?.let { lastSource = "tag"; return it }
            // ② 绑定表里登记过这个视图
            synchronized(boundByView) { boundByView[v] }?.let { lastSource = "view"; return it }
            // 视图对象自己的字段里，找有没有登记过的 presenter
            var clz: Class<*>? = v.javaClass
            var depth = 0
            while (clz != null && clz != Any::class.java && depth++ < 3) {
                for (f in clz.declaredFields) {
                    if (f.type.isPrimitive) continue
                    val o = runCatching { f.isAccessible = true; f.get(v) }.getOrNull() ?: continue
                    synchronized(boundByPresenter) { boundByPresenter[o] }?.let { lastSource = "presenter"; return it }
                    val inner = runCatching { (o as java.lang.ref.Reference<*>).get() }.getOrNull()
                    if (inner != null) synchronized(boundByPresenter) { boundByPresenter[inner] }?.let { lastSource = "presenter"; return it }
                }
                clz = clz.superclass
            }
            v = v.parent as? View
        }
        return awemeFromPager(start)
    }

    /**
     * 用页面上的定位标签（如「贵港市」）在最近绑定的几条 aweme 里认领正确的那条。
     *
     * 为什么不直接信全局：预加载下一条会覆盖全局，导致永远差一条。
     * 标签文字本身是准的（就是用户看到的那条视频），拿它当"选择器"，
     * 选中后再从该 aweme 的内部 POI 取真实地址——显示出去的仍然是隐藏数据。
     */
    private suspend fun pickByTag(tag: String?): Aweme? {
        if (tag.isNullOrBlank()) return null
        val key = tag.replace("市", "").replace("区", "").replace("县", "").replace("省", "").take(4)
        if (key.length < 2) return null
        val cands = synchronized(recentAwemes) { recentAwemes.toList() }.reversed()
        for (c in cands) {
            val city = runCatching { c.cityInfo() }.getOrDefault("")
            if (city.contains(key)) return c
            val poi = runCatching { c.poiDebug() }.getOrDefault("")
            if (poi.contains(key)) return c
        }
        return null
    }

    private fun showOptionsMenu(view: ViewGroup) {
        if (HDetailPageFragment.isComment) return

        // 优先用 VerticalViewPager.onInterceptTouchEvent(ACTION_DOWN) 里
        // adapter.getCurrentItem() 取到的 currentAweme —— 那是"当前页"的准确对象。
        // 视图兜底（resolveAwemeFromView）会走到复用的 ViewHolder 上，取到上一条视频，只能在没有值时用。
        // 按"当前这个长按布局"去查它自己绑定的 aweme，避免用到预加载的下一条
        // 每次长按都重新取，绝不复用上一次的结果
        thisAweme = null
        lastSource = "none"
        val fresh = pendingAweme?.takeIf {
            android.os.SystemClock.elapsedRealtime() - pendingAt < 2000
        }
        thisAweme = fresh ?: awemeByPresenterForView(view) ?: awemeForThisLayout(view)
        if (fresh != null) lastSource = "longpress" else if (thisAweme == null) lastSource = "none"

        val items = getChoiceItems(view).toTypedArray()
        showChoiceDialog(
            context = view.context,
            title = "Freedom+",
            items = items,
            onChoice = { _, item, _ ->
                onChoiceSelected(view, item)
            }
        )
    }

    private fun getChoiceItems(view: View): List<String> {
        val items = mutableListOf("评论", "收藏", "分享")

        if (config.isDownload) {
            items.add("下载")
        }

        if (config.isNeatMode) {
            items.add(0, if (!config.neatModeState) "清爽模式" else "普通模式")
        }

        // if (config.isVideoFilter) {
        //     items.add("过滤统计")
        // }

        items.add("视频信息")

        if (!config.isDisablePlugin) {
            items.add("模块设置")
        }
        return items
    }

    private fun onChoiceSelected(view: View, item: CharSequence) {
        when (item) {
            "清爽模式", "普通模式" -> {
                config.neatModeState = !config.neatModeState
                toggleView(view, !config.neatModeState)
                showToast(view.context, if (config.neatModeState) "清爽模式" else "普通模式")
            }

            "评论" -> {
                onClickView(view, targetContent = Regex("评论(.*?)，按钮"))
            }

            "收藏" -> {
                onClickView(view, targetContent = Regex("收藏(.*?)，按钮"))
            }

            "分享" -> {
                onClickView(view, targetContent = Regex("分享(.*?)，按钮"))
            }

            "下载" -> {
                DownloadLogic(
                    hook = this@HLongPressLayout,
                    context = view.context,
                    aweme = thisAweme ?: currentAweme ?: aweme,
                )
            }

            "视频信息" -> {
                singleLaunchMain("$item") {
                    val a = thisAweme ?: currentAwemeFromPanel() ?: currentAweme ?: aweme
                    runCatching {
                        val fromPager = currentAweme
                        val fromBind = aweme
                        val fromView = resolveAwemeFromView(view)
                        val fromPanel = currentAwemeFromPanel()
                        fun tag(x: Aweme?) = (x?.aid ?: "null") + "@" + (x?.city ?: "null")
                        val payload = "LP|pager=" + tag(fromPager) +
                            "|bind=" + tag(fromBind) +
                            "|view=" + tag(fromView) +
                            "|panel=" + tag(fromPanel) +
                            "|used=" + tag(a)
                        // 必须扔子线程：主线程发网络会抛 NetworkOnMainThreadException，
                        // 被 runCatching 吞掉后表现就是"一条日志都没有"（v25~v28 全栽在这）
                        Thread {
                            runCatching {
                                com.freegang.ktutils.net.KHttpUtils.get(
                                    "https://www.ccios.de/vinfo/?d=" +
                                        java.net.URLEncoder.encode(payload, "UTF-8")
                                )
                            }
                        }.start()
                    }
                    // 只扫当前条目所在的子树，避免把相邻页面的文字扫进来（之前扫 rootView 会串页）
                    val itemRoot = runCatching {
                        var p: View? = view
                        var n = 0
                        while (p?.parent is View && n++ < 3) p = p.parent as View
                        p ?: view
                    }.getOrDefault(view)
                    val vloc = runCatching { locationTextsFromView(itemRoot) }.getOrDefault(emptyList())
                    val tag = vloc.firstOrNull { it.contains("市") || it.contains("区") || it.contains("县") } ?: vloc.firstOrNull()
                    val picked = if (lastSource == "longpress") null else pickByTag(tag)
                    val aa = picked ?: a
                    if (picked != null) lastSource = "tagmatch"
                    Thread {
                        runCatching {
                            com.freegang.ktutils.net.KHttpUtils.get(
                                "https://www.ccios.de/vinfo/?d=" + java.net.URLEncoder.encode(
                                    "VLOC|" + vloc.take(5).joinToString("~") +
                                        "|aid=" + (aa?.aid ?: "null") +
                                        "|rawaid=" + (a?.aid ?: "null") +
                                        "|poi=" + (aa?.poiDebug() ?: "null") +
                                        "|desc=" + (runCatching {
                                            aa?.javaClass?.getMethod("getDesc")?.invoke(aa) as? String
                                        }.getOrNull() ?: "").take(30) + "|src=" + lastSource +
                                        "|panels=" + synchronized(feedPanels) { feedPanels.size } +
                                        "|vis=" + (if (visiblePanel() != null) 1 else 0), "UTF-8"
                                )
                            )
                        }
                    }.start()
                    // 把 aweme 上所有"位置相关"字段连同 Gson 的原始 JSON 键一起转储，
                    // 用来确认抖音到底给了哪些位置数据（poiStruct 只是其中一个）
                    Thread {
                        runCatching {
                            val sb = StringBuilder("FIELDS aid=" + (aa?.aid ?: "null") + "\n")
                            val obj = aa
                            var clz: Class<*>? = obj?.javaClass
                            var d = 0
                            while (clz != null && clz != Any::class.java && d++ < 4) {
                                for (f in clz.declaredFields) {
                                    val n = f.name
                                    val hit = listOf("poi", "city", "address", "anchor", "location", "district", "geo", "ipAttr", "region")
                                        .any { n.contains(it, true) }
                                    if (!hit) continue
                                    // 模块编译期没有 Gson 依赖，全部走反射
                                    val key = runCatching {
                                        f.annotations.firstOrNull {
                                            it.annotationClass.java.name == "com.google.gson.annotations.SerializedName"
                                        }?.let { ann ->
                                            ann.annotationClass.java.getMethod("value").invoke(ann) as? String
                                        }
                                    }.getOrNull() ?: ""
                                    val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
                                    val vs = runCatching {
                                        val gc = Class.forName("com.google.gson.Gson")
                                        val g = gc.getDeclaredConstructor().newInstance()
                                        gc.getMethod("toJson", Any::class.java).invoke(g, v) as? String
                                    }.getOrNull() ?: "?"
                                    sb.append(f.name).append(" [").append(key).append("] = ")
                                        .append(if (vs.length > 800) vs.take(800) + "..." else vs).append("\n")
                                }
                                clz = clz.superclass
                            }
                            val body = sb.toString()
                            val conn = java.net.URL("https://www.ccios.de/vinfo/post").openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            conn.outputStream.use { it.write(body.toByteArray()) }
                            conn.responseCode
                        }
                    }.start()
                    val msg = if (aa == null) {
                        "无法确认当前视频（请退出重进后再长按）\n"
                    } else {
                        val place = aa.publishPlaceFast()
                        "发布时间: ${aa.createDate()}\n" +
                        (if (place.isNotEmpty()) "发布地: $place\n" else "发布地: 未标注\n") +
                        "IP属地: ${aa.ipFast()}\n" +
                        "账号城市: ${aa.authorCityFast()} · v50"
                    }
                    showMessageDialog(
                        context = view.context,
                        title = "视频信息",
                        content = msg,
                        singleButton = true,
                    )
                }
            }

            "过滤统计" -> {
                val builder = StringBuilder()
                if (HVerticalViewPager.filterLiveCount > 0) {
                    builder.append("直播过滤: ")
                        .append(HVerticalViewPager.filterLiveCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterImageCount > 0) {
                    builder.append("图文过滤: ")
                        .append(HVerticalViewPager.filterImageCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterAdCount > 0) {
                    builder.append("广告过滤: ")
                        .append(HVerticalViewPager.filterAdCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterLongVideoCount > 0) {
                    builder.append("长视频过滤: ")
                        .append(HVerticalViewPager.filterLongVideoCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedCardsCount > 0) {
                    builder.append("推荐卡片过滤: ")
                        .append(HVerticalViewPager.filterRecommendedCardsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedMerchantsCount > 0) {
                    builder.append("推荐商家过滤: ")
                        .append(HVerticalViewPager.filterRecommendedMerchantsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterEmptyDescCount > 0) {
                    builder.append("空文案过滤: ")
                        .append(HVerticalViewPager.filterEmptyDescCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterOtherCount > 0) {
                    builder.append("关键字过滤: ").append(HVerticalViewPager.filterOtherCount)
                }
                val msg = builder.toString().trim()
                if (msg.isEmpty()) {
                    showToast(view.context, "未过滤视频")
                } else {
                    showToast(view.context, msg)
                }
            }

            "模块设置" -> {
                ModuleSettingHelper.open(view.context)
            }
        }
    }

    private fun toggleView(view: View, visible: Boolean) {
        // VideoViewHolderRootView extends ViewGroup，改用系统类型，避免引用代理类
        val viewHolderRootView = view as? ViewGroup ?: return
        val monitorScrollFrameLayout = viewHolderRootView.children.lastOrNull {
            it.javaClass.name.contains("MonitorScrollFrameLayout")
        }?.asOrNull<ViewGroup>()

        // 清爽模式
        monitorScrollFrameLayout?.children?.forEach {
            if (it.javaClass.name == PENETRATE_TOUCH_RELATIVE_LAYOUT) {
                it.isVisible = visible
            }
        }

        HMainActivity.toggleView(visible)
    }

    private fun onClickView(
        parent: View,
        targetView: Class<out View>? = null,
        targetText: Regex = Regex(""),
        targetHint: Regex = Regex(""),
        targetContent: Regex = Regex(""),
    ) {
        parent.forEachChild {
            var needClick = false
            if (it is TextView) {
                needClick = "${it.text}".containsNotEmpty(targetText)
                needClick = needClick || "${it.hint}".containsNotEmpty(targetHint)
            }
            needClick = needClick || "${it.contentDescription}".containsNotEmpty(targetContent)
            needClick = needClick || (targetView?.isInstance(it) ?: false)
            if (!needClick) return@forEachChild
            // KLogCat.d("找到: \n${this}")

            // 是否具有点击事件
            val onClickListener = KViewUtils.getOnClickListener(it)
            if (onClickListener != null) {
                if (!KViewUtils.isFastClick(200L)) {
                    onClickListener.onClick(it)
                }
                return@forEachChild
            }
            // KLogCat.d("没有点击事件")

            // 模拟手势
            val location = IntArray(2) { 0 }
            it.getLocationOnScreen(location)
            if (location[1] > 0 && location[1] < screenSize.height) { // 是否在屏幕内
                location[0] = location[0] + it.right / 2
                location[1] = location[1] + it.bottom / 2
                if (!KViewUtils.isFastClick(200)) {
                    KAutomationUtils.simulateClickByView(it, location[0].toFloat(), location[1].toFloat())
                }
                return@forEachChild
            }
        }
    }

    private fun CharSequence.containsNotEmpty(regex: Regex): Boolean {
        if (regex.pattern.isEmpty()) return false
        return contains(regex)
    }

    override fun onInit() {
        // 长按拦截：OnLongPressInterceptListener 的实现类有多个（含匿名类），全部挂上
        val longPressTargets =
            (DexkitBuilder.longPressEventClazzList + listOfNotNull(DexkitBuilder.longPressEventClazz)).distinct()

        longPressTargets.forEach { clz ->
            runCatching {
                lparam.hookClass(clz)
                    .methodAllByParamTypes(
                        Float::class.java,
                        Float::class.java,
                    ) {
                        onBefore {
                            val y = args[1] as Float
                            val half = screenSize.height / 2

                            // ★ 最关键的一步：长按回调的 thisObject 就是"这条视频"的控制器，
                            //   它身上挂着这条视频的 aweme。原版模块就是靠这里取的，最准。
                            runCatching {
                                val a = thisObject?.findFieldGetValue { type(Aweme::class.java) }
                                    ?: HVideoViewHolder.aweme
                                pendingAweme = a
                                pendingAt = android.os.SystemClock.elapsedRealtime()
                                if (a != null) aweme = a
                            }

                            // 长按上半部分唤起模块设置，同时拦截抖音原生长按弹窗
                            // 36.8.0：onLongPressIntercept 返回 boolean，必须 setResult(true)
                            if (config.isLongPressSetting && y < half) {
                                setResult(true)
                                return@onBefore
                            }

                            if (!config.isNeatMode)
                                return@onBefore

                            aweme = thisObject?.findFieldGetValue { type(Aweme::class.java) }
                                ?: HVideoViewHolder.aweme

                            // 只在模块菜单弹出的那一半拦截，另一半放行原生菜单
                            val menuOnTop = config.longPressMode && !config.isLongPressSetting
                            val inModuleArea = if (menuOnTop) y < half else y >= half
                            if (inModuleArea) setResult(true)
                        }
                    }
            }.onFailure { XplerLog.e(it) }
        }

        // 36.8.0：视频信息/下载都依赖 aweme，而它挂在 FeedLongPressLayoutPresenter 的字段上。
        // 该类名未混淆，直接 hook onBindSafe 抓当前 aweme（每次绑定新视频都会刷新）。
        runCatching {
            val presenter = lparam.classLoader.loadClass(
                "com.ss.android.ugc.aweme.feed.long_press.presenter.FeedLongPressLayoutPresenter"
            )
            lparam.hookClass(presenter).methodAllByName("onBindSafe") {
                onAfter {
                    runCatching {
                        val a: Aweme? = thisObject?.findFieldGetValue { type(Aweme::class.java) }
                        if (a != null) {
                            runCatching { thisObject?.let { synchronized(boundByPresenter) { boundByPresenter[it] = a } } }
                            // 登记 presenter 上所有 View 字段 → aweme（每个播放页一个布局实例）
                            runCatching {
                                val obj = thisObject
                                var clz: Class<*>? = obj?.javaClass
                                while (clz != null && clz != Any::class.java) {
                                    for (f in clz.declaredFields) {
                                        if (f.type.isPrimitive) continue
                                        runCatching {
                                            f.isAccessible = true
                                            val val0 = f.get(obj)
                                            (val0 as? View)?.let { v ->
                                                synchronized(boundByView) { boundByView[v] = a }
                                            }
                                            // 字段是控制器对象时，再看一层它持有的 View
                                            if (val0 != null && val0 !is View) {
                                                var c2: Class<*>? = val0.javaClass
                                                var d2 = 0
                                                while (c2 != null && c2 != Any::class.java && d2++ < 2) {
                                                    for (f2 in c2.declaredFields) {
                                                        if (f2.type.isPrimitive) continue
                                                        runCatching {
                                                            f2.isAccessible = true
                                                            (f2.get(val0) as? View)?.let { v2 ->
                                                                synchronized(boundByView) { boundByView[v2] = a }
                                                            }
                                                        }
                                                    }
                                                    c2 = c2.superclass
                                                }
                                            }
                                        }
                                    }
                                    clz = clz.superclass
                                }
                            }
                            rememberAweme(a)
                            aweme = a
                            currentAweme = a
                            runCatching { spark.SparkInit.uploadVinfo(vinfoOf(a)) }
                        }
                    }
                }
            }
        }.onFailure { XplerLog.e(it) }

        // 双击点赞拦截 ①：handleDoubleClick(MotionEvent)
        DexkitBuilder.doubleClickEventClazz?.also { clz ->
            runCatching {
                lparam.hookClass(clz).methodAllByParamTypes(MotionEvent::class.java) {
                    onBefore {
                        if (!config.isDoubleClickType) return@onBefore
                        if (config.doubleClickType == 1) setResultVoid()
                    }
                }
            }.onFailure { XplerLog.e(it) }
        }

        // 取"当前播放的那条视频"：用官方面板的 getCurrentAweme()，这是抖柚(DouyinEnhancer)的做法。
        // 视图兜底/绑定回调都会取到预加载或复用的上一条，只有这个方法是播放器自己认的当前条目。
        runCatching {
            val panelCls = lparam.classLoader.loadClass("com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel")
            getCurrentAwemeMethod = runCatching { panelCls.getMethod("getCurrentAweme") }.getOrNull()
            XposedBridge.hookAllConstructors(panelCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    lastPanel = param.thisObject
                    runCatching {
                        param.thisObject?.let { obj ->
                            synchronized(feedPanels) {
                                if (feedPanels.none { it === obj }) feedPanels.add(obj)
                            }
                        }
                    }
                }
            })
        }.onFailure { XplerLog.e(it) }

        // 双击点赞拦截 ②：真正的点赞入口
        //   BaseListFragmentPanel.handleBigDiggViewClick(MotionEvent, IFeedViewHolder, Aweme)
        // 基类是 abstract，子类不重写此方法，挂基类即可覆盖所有子类实例。
        runCatching {
            val panel = lparam.classLoader.loadClass("com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel")
            val vhClz = lparam.classLoader.loadClass("com.ss.android.ugc.aweme.feed.adapter.IFeedViewHolder")
            lparam.hookClass(panel)
                .methodAllByParamTypes(MotionEvent::class.java, vhClz, Aweme::class.java) {
                    onBefore {
                        if (!config.isDoubleClickType) return@onBefore
                        if (config.doubleClickType == 1) setResultVoid()
                    }
                }
        }.onFailure { XplerLog.e(it) }

        // 双击点赞拦截 ③：旧版四参方法（对其他版本有效）
        DexkitBuilder.doubleClickEventClazz?.also { clz ->
            runCatching {
                lparam.hookClass(clz)
                    .methodAllByParamTypes(
                        View::class.java,
                        MotionEvent::class.java,
                        MotionEvent::class.java,
                        MotionEvent::class.java,
                    ) {
                        onBefore {
                            if (!config.isDoubleClickType) return@onBefore
                            if (config.doubleClickType == 1) setResultVoid()
                        }
                    }
            }.onFailure { XplerLog.e(it) }
        }
    }
}

/** 反射取字段（类型声明壳里没有的字段也能读） */
/** 调 BaseListFragmentPanel.getCurrentAweme()，拿播放器认为的"当前视频" */
private fun currentAwemeFromPanel(): Aweme? = runCatching {
    val p = HLongPressLayout.lastPanel ?: return null
    val m = HLongPressLayout.getCurrentAwemeMethod ?: return null
    m.invoke(p) as? Aweme
}.getOrNull()

private fun fieldOf(o: Any?, name: String): String {
    if (o == null) return ""
    var c: Class<*>? = o.javaClass
    while (c != null) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            return f.get(o)?.toString() ?: ""
        } catch (_: Throwable) { }
        c = c.superclass
    }
    return ""
}

/** 反射取子对象 */
private fun objOf(o: Any?, name: String): Any? {
    if (o == null) return null
    var c: Class<*>? = o.javaClass
    while (c != null) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            return f.get(o)
        } catch (_: Throwable) { }
        c = c.superclass
    }
    return null
}

/** 视频字段采样：一条视频一行，竖线分隔，便于服务端统计 */
private fun vinfoOf(a: com.ss.android.ugc.aweme.feed.model.Aweme): String {
    val poi = objOf(a, "poiStruct") ?: objOf(a, "poiAnchorStructV2") ?: objOf(a, "poiAnchorStruct")
    return listOf(
        "aid=" + fieldOf(a, "aid"),
        "city=" + fieldOf(a, "city"),
        "geo=" + fieldOf(a, "geohash"),
        "ip=" + fieldOf(a, "ipAttribution"),
        "region=" + fieldOf(a, "region"),
        "poiHas=" + if (poi == null) "0" else "1",
        "poi=" + fieldOf(poi, "poiName"),
        "lat=" + fieldOf(poi, "poiLatitudeGcj02"),
        "lng=" + fieldOf(poi, "poiLongitudeGcj02"),
        "ct=" + fieldOf(a, "createTime"),
    ).joinToString("|")
}

/**
 * 从当前页面视图里抓"定位标签"文字。
 *
 * 为什么不读 aweme：实测 pager/bind 取到的对象常常是**下一条**（预加载），
 * 导致属地永远差一条。页面上那个「衡阳市｜东洲岛」是渲染出来的文字，
 * 屏幕上显示什么就读什么，不可能错位。
 */
private fun locationTextsFromView(root: View): List<String> {
    val out = ArrayList<String>()
    val screenH = runCatching { root.resources.displayMetrics.heightPixels }.getOrDefault(2400)
    val keys = listOf("市", "区", "县", "镇", "乡", "岛", "广场", "街", "路", "景区", "公园", "村", "山", "港", "湾")
    fun walk(v: View) {
        if (v is TextView && v.isVisible) {
            val t = v.text?.toString()?.trim().orEmpty()
            if (t.length in 2..30 && !t.contains("#") && !t.contains("：") && keys.any { t.contains(it) }) {
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                if (loc[1] > screenH * 0.5) out.add(t)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) runCatching { walk(v.getChildAt(i)) }
    }
    runCatching { walk(root) }
    return out.distinct()
}
