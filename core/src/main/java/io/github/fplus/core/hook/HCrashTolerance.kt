package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager
import com.ss.android.ugc.aweme.kiwi.model.QModel
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.CallMethods
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/// 崩溃容错，处理官方可能造成的系列崩溃问题
class HCrashTolerance {
    companion object {
        const val TAG = "HCrashTolerance"
    }

    val config get() = ConfigV1.get()

    init {
        HPoiFeed()
        HLivePhoto()
        HTabLanding()
        HFeedPagerPopulate()
    }

    inner class HPoiFeed : BaseHook(), CallMethods {
        override fun setTargetClass(): Class<*> {
            return findClass("com.ss.android.ugc.aweme.poi.anchor.poi.flavor.PoiFeedAnchor")
        }

        override fun callOnBeforeMethods(params: MethodParam) {

        }

        override fun callOnAfterMethods(params: MethodParam) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    inner class HLivePhoto : BaseHook() {

        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.livePhotoClazz ?: NoneHook::class.java
        }

        @OnAfter
        fun methodAfter(params: MethodParam, qModel: QModel?) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    inner class HTabLanding : BaseHook() {

        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.tabLandingClazz ?: NoneHook::class.java
        }

        // 40.6.0 适配：原写法 `@OnAfter` + `(params, item: VideoItemParams?)` 会被 xpler 解析成
        // paramTypes=[VideoItemParams]，而目标类（TabLandGuideModule）在 40.6.0 的 22 个方法
        // 无一接收该类型，必然绑不上。改为按方法名绑定且不声明额外参数（只按名字过滤）。
        @OnAfter("onCreateView")
        fun methodAfter(params: MethodParam) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    /**
     * 【崩溃容错】`VerticalViewPager.populate` 的 PagerAdapter 数量失配断言。
     *
     * ## 崩的是什么
     * ```
     * java.lang.IllegalStateException: The application's PagerAdapter changed the adapter's
     * contents without calling PagerAdapter#notifyDataSetChanged! Expected adapter item count: 13,
     * found: 15 Pager id: com.ss.android.ugc.aweme:id/viewpager
     * Pager class: class com.ss.android.ugc.aweme.common.widget.RTViewPager
     * Problematic adapter: class X.C1790160wg5
     *   at VerticalViewPager.LJJIJIL(populate)
     *   at VerticalViewPager.LJJIJIIJIL         ← 只有一句 LJJIJIL(this.h)
     *   at X.1YgM.run                           ← 真身 X.RunnableC2797451YgM，VerticalViewPager 的私有
     *   at X.1YgP.run                              Runnable（AOSP mEndScrollRunnable 那一套：
     *   at bytex.async.stack.broken_chain...       setScrollState(IDLE) 后 populate），被 ByteX
     *                                              broken_chain 拆成了两个 run 块
     * ```
     *
     * ## 这不是抖音的「检测」
     * `LJJIJIL(int)` = `populate(int newCurrentItem)`，头部就是 AOSP ViewPager 的原生自洽检查：
     * ```
     * int count = this.g.getCount();
     * if (count == this.b) { ...正常装载... }   // b = mExpectedAdapterCount
     * else throw new IllegalStateException("... Expected adapter item count: " + this.b
     *                                      + ", found: " + count + ...);
     * ```
     * `this.b` **只在** `notifyDataSetChanged()` → `dataSetChanged()` 里被写成当时的 `getCount()`。
     * 契约是「两次 notify 之间 getCount() 恒定」；任何在两次 notify 之间改变 `getCount()` 的东西
     * ——模块 hook 或宿主自身的插入/删除竞态——都会在滚动结束那一刻炸掉。
     * 与签名校验、反调试、账号风控都无关。
     *
     * ## 兜底策略
     * 1. 只吞这一条（`IllegalStateException` 且 message 含 `PagerAdapter`），别的异常照旧抛出，
     *    避免把真实问题藏起来；
     * 2. 下一帧（post 到主线程，避免在 populate 里再进 populate）调一次
     *    `adapter.notifyDataSetChanged()`，让抖音自己把 `b` 刷成当前 `getCount()`，
     *    pager 随后能正常装载 —— 不依赖任何混淆字段名，版本无关。
     *
     * 注意：这只是「不崩」的安全网。真凶（旧版 `HFeedAdFilter` 改写 `getCount()`）必须从根上
     * 修掉，否则表现为「不崩但翻不动页」。
     */
    inner class HFeedPagerPopulate : BaseHook() {
        private var swallowed = 0
        private var lastRecoverAt = 0L

        override fun setTargetClass(): Class<*> {
            return VerticalViewPager::class.java
        }

        /** `LJJIJIL(int)` 是 final public 的 populate；onAfter 里能拿到被抛出的异常 */
        @OnAfter("LJJIJIL")
        fun populateAfter(params: MethodParam) {
            val th = params.throwable ?: return
            if (th !is IllegalStateException) return
            if (th.message?.contains("PagerAdapter") != true) return

            params.setThrowable(null)
            swallowed++
            KLogCat.tagI(
                TAG,
                "已吞掉 PagerAdapter 数量失配异常(第${swallowed}次): " +
                    "${th.message?.substringBefore(" Pager id").orEmpty()}",
            )

            // 限速：notify 本身会重挂 b 并触发新一轮 populate，若 count 仍在抖动就别死循环
            val now = System.currentTimeMillis()
            if (now - lastRecoverAt < 300L) return
            lastRecoverAt = now

            val pager = params.thisObject ?: return
            refresh {
                runCatching {
                    val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager)
                        ?: return@runCatching
                    adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
                }.onFailure { XplerLog.e(it) }
            }
        }
    }
}
