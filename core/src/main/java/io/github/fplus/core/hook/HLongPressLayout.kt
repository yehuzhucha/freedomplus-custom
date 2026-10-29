package io.github.fplus.core.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.freegang.extension.asOrNull
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

    private fun showOptionsMenu(view: ViewGroup) {
        if (HDetailPageFragment.isComment) return

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
                    aweme = aweme,
                )
            }

            "视频信息" -> {
                singleLaunchMain("$item") {
                    val msg = "视频属地: ${aweme?.cityInfo()}\n发布时间: ${aweme?.createDate()}".trim()
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
                        if (a != null) aweme = a
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
