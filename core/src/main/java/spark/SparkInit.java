package spark;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 续火花 v7 —— 走抖音【官方消息发送通道】
 *
 * 与 v1~v6 的根本区别：不再打开聊天页、完全不碰 UI。
 * 直接调用 IM SDK 自己的构造器 + 发送器：
 *
 *   C0uEM.LIZ.LIZLLL()                    -> IIMSdkClient     (静态单例)
 *     .getBeanCreateService()             -> IBeanCreateService
 *       .createMessageBuilder()           -> Message$Builder
 *         .LIZ(conversationId)
 *         .msgType(7)                     (7 = 文字)
 *         .content(text)
 *         .build()                        -> Message
 *     .getIMHandlerCenter()               -> IMHandlerCenter
 *       .sendMessage(Message, Map)        -> 发出
 *
 * 以上每一环均已在目标机抖音 30.9.0 的 dex 中逐项核验存在。
 */
public class SparkInit implements IXposedHookLoadPackage {

    private static final String HOST  = "https://www.ccios.de";
    private static final String TOKEN = "__SPARK_TOKEN__";
    private static final String PKG   = "com.ss.android.ugc.aweme";

    private static final String DIR   = "/sdcard/Download";
    // 这些路径会被 redirectToAppDir() 改成 App 私有外部目录（Android 13+ 写不了 /sdcard/Download，
    // 导致「已发记录」落盘失败 -> 每次重开抖音都从 1/10 重发，好友收到重复消息）
    private static volatile String OFF   = DIR + "/spark.off";
    private static volatile String LOGF  = DIR + "/spark.log";
    private static volatile String LOCK  = DIR + "/spark.lock";
    private static volatile String DAYF  = DIR + "/spark.day";
    private static volatile String SENTF = DIR + "/spark-sent.json";
    private static volatile String IDSF  = DIR + "/spark-ids.json";

    private static final String CLS_MGR   = "X.0uEM";
    private static final String FLD_MGR   = "LIZ";
    private static final String M_GETCLI  = "LIZLLL";
    private static final String M_BEANSVC = "getBeanCreateService";
    private static final String M_NEWBLD  = "createMessageBuilder";
    private static final String M_SETCID  = "LIZ";
    private static final String M_MSGTYPE = "msgType";
    private static final String M_CONTENT = "content";
    private static final String M_BUILD   = "build";
    private static final String M_CENTER  = "getIMHandlerCenter";
    private static final String M_SEND    = "sendMessage";
    private static final String M_GETCLM  = "getConversationListModel";
    private static final String M_GETCONV = "getConversation";
    private static final String M_GETALL  = "getAllConversationSyncNoSort";
    private static final int    TYPE_TEXT = 7;       // LEGACY_MESSAGE_TYPE_TEXT —— 30.9.0 实际用的文字类型（10001 会走未知类型兜底，显示 null）
    // 自己的 uid —— 单聊会话ID = 0:1:<min(自己,对方)>:<max(自己,对方)>，用它反推会话ID
    private static final String ME_UID = "111188513589";

    private static volatile boolean running = false;
    /** 云端配置快照（sendMode / diag 等运行时开关） */
    private static volatile JSONObject CFG = null;
    // 采集队列：hook 里只入队（微秒级），重活交给后台线程 —— 绝不在 UI 线程做反射
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> QUEUE =
            new java.util.concurrent.ConcurrentLinkedQueue<Object[]>();
    private static volatile boolean QUEUE_STARTED = false;
    private static final java.util.concurrent.atomic.AtomicInteger DUMPED =
            new java.util.concurrent.atomic.AtomicInteger();
    // 消费线程标记：消费线程内部的调用不再入队，防自激循环
    private static final ThreadLocal<Boolean> IN_CONSUMER = new ThreadLocal<Boolean>();
    private static final java.util.Set<String> SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.Map<String, Integer> RETRY =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private static final java.util.Map<String, String> IDS =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static volatile boolean PENDING = false;
    private static volatile boolean COLLECTOR_STARTED = false;

    /** Android 13+ 写不了 /sdcard/Download，回退到 App 私有外部目录（文件管理器可见） */
    private static volatile String LOG_PATH = LOGF;

    private static void log(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "  " + s;
        XposedBridge.log("Spark: " + s);
        try {
            FileOutputStream fo = new FileOutputStream(LOG_PATH, true);
            fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Throwable ignored) { }
    }

    /** 拦截「应用版本过低 / 非官方渠道」弹窗：Dialog.show 之后扫文本，命中就 dismiss */
    private void hookVersionDialog(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(android.app.Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.app.Dialog d = (android.app.Dialog) p.thisObject;
                    String text = dialogText(d);
                    if (text != null && (text.contains("版本过低") || text.contains("非官方渠道")
                            || text.contains("官方应用商店"))) {
                        log("拦截弹窗: " + text.substring(0, Math.min(50, text.length())));
                        d.dismiss();
                    }
                } catch (Throwable ignored) { }
            }
        });
        log("已挂钩 版本过低弹窗拦截");
    }

    private static String dialogText(android.app.Dialog d) {
        try {
            android.view.View root = d.getWindow() == null ? null : d.getWindow().getDecorView();
            if (root == null) return null;
            StringBuilder sb = new StringBuilder();
            walkText(root, sb);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void walkText(android.view.View v, StringBuilder sb) {
        if (v == null) return;
        if (v instanceof android.widget.TextView) {
            CharSequence c = ((android.widget.TextView) v).getText();
            if (c != null) sb.append(c).append(' ');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkText(g.getChildAt(i), sb);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        log("==== 续火花 v9h 载入（进程 " + lpp.processName + "）====");

        if (lpp.processName != null && lpp.processName.contains(":")) {
            log("非主进程，不跑每日任务");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Application app = (Application) param.thisObject;
                    redirectToAppDir(app);
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { daily(app); } catch (Throwable t) { log("每日任务异常: " + t); }
                        }
                    }, "spark-daily").start();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { startCollector(app.getClassLoader()); } catch (Throwable t) { log("采集线程异常: " + t); }
                        }
                    }, "spark-collect").start();
                }
            });
        } catch (Throwable t) {
            log("挂钩 Application.onCreate 失败: " + t);
        }

        try {
            hookVersionDialog(lpp.classLoader);
        } catch (Throwable t) {
            log("挂版本弹窗失败: " + t);
        }
    }

    /** 把全部状态文件重定向到 App 私有外部目录（Android 13+ 写不了 /sdcard/Download） */
    private static volatile boolean dirReady = false;

    private static void redirectToAppDir(Application app) {
        if (dirReady) return;
        try {
            File ext = app.getExternalFilesDir(null);
            if (ext != null) {
                String base = ext.getAbsolutePath();
                OFF = base + "/spark.off";
                LOGF = base + "/spark.log";
                LOCK = base + "/spark.lock";
                DAYF = base + "/spark.day";
                SENTF = base + "/spark-sent.json";
                IDSF = base + "/spark-ids.json";
                LOG_PATH = LOGF;
                dirReady = true;
                log("数据目录: " + base);
            }
        } catch (Throwable ignored) { }
    }

    private void daily(Application app) {
        redirectToAppDir(app);
        if (new File(OFF).exists()) { log("关停开关存在（" + OFF + "），跳过"); return; }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        if (today.equals(readFile(DAYF).trim())) { log("今天已跑过，跳过"); progress("already", today + " 已跑过"); return; }

        try {
            File lf = new File(LOCK);
            if (lf.exists()) {
                long age = (System.currentTimeMillis() - lf.lastModified()) / 1000L;
                if (age < 300) { log("已有实例在跑（锁 " + age + " 秒前），退出"); return; }
            }
            writeFile(LOCK, String.valueOf(System.currentTimeMillis()));
        } catch (Throwable ignored) { }

        if (running) { log("本进程已有任务在跑，退出"); return; }
        running = true;

        try {
            log("★ 今天第一次启动抖音，开始续火花");
            progress("start", "今日首次启动");
            log("等 10 秒让进程稳定…");
            Thread.sleep(10000L);

            progress("after_wait", "60 秒等待结束，开始拉配置");
            JSONObject cfg = fetchConfig();
            if (cfg == null) { log("拿不到云端配置，放弃"); progress("fail", "拿不到云端配置"); return; }
            CFG = cfg;
            if (!cfg.optBoolean("enabled", true)) { log("云端开关是关的，跳过"); progress("skip", "云端开关关闭"); return; }

            String text = "[续火花吧]";
            JSONArray msgs = cfg.optJSONArray("messages");
            if (msgs != null && msgs.length() > 0) {
                String m = msgs.optString(0, "").trim();
                if (m.length() > 0) text = m;
            }

            JSONArray ids = cfg.optJSONArray("targetIds");
            if (ids == null || ids.length() == 0) {
                log("云端还没勾选好友（targetIds 为空），放弃");
                progress("skip", "targetIds 为空");
                return;
            }

            JSONArray gaps = cfg.optJSONArray("gapSeconds");
            int gapMin = 25, gapMax = 60;
            if (gaps != null && gaps.length() >= 2) {
                gapMin = Math.max(5, gaps.optInt(0, 25));
                gapMax = Math.max(gapMin, gaps.optInt(1, 60));
            }

            List<String> targets = new ArrayList<String>();
            for (int i = 0; i < ids.length(); i++) {
                String s = ids.optString(i, "").trim();
                if (s.length() > 0) targets.add(s);
            }
            log("共 " + targets.size() + " 个好友，文案: " + text);
            progress("targets", "共 " + targets.size() + " 个好友，文案: " + text);

            ClassLoader cl = app.getClassLoader();
            Random rnd = new Random();
            int ok = 0, fail = 0;

            Set<String> sentToday = loadSentToday();
            if (sentToday.containsAll(targets)) {
                log("今天已全部发过（" + sentToday.size() + " 个），跳过");
                progress("already", "今天已全部发过 " + sentToday.size() + " 个");
                writeFile(DAYF, today);
                return;
            }
            if (!sentToday.isEmpty()) log("今天已发过 " + sentToday.size() + " 个，本次将跳过");
            int skipped = 0;

            for (int i = 0; i < targets.size(); i++) {
                String cid = targets.get(i);
                if (sentToday.contains(cid)) {
                    skipped++;
                    log("(" + (i + 1) + "/" + targets.size() + ") 今天已发过，跳过: " + cid);
                    continue;
                }
                try {
                    sendText(cl, cid, text);
                    markSent(cid);
                    ok++;
                    log("(" + (i + 1) + "/" + targets.size() + ") 已提交: " + cid);
                    progress("send_ok", (i + 1) + "/" + targets.size() + " " + cid);
                } catch (Throwable t) {
                    fail++;
                    log("(" + (i + 1) + "/" + targets.size() + ") 失败: " + cid + " -> " + t);
                    progress("send_fail", cid + " -> " + t);
                }
                if (i < targets.size() - 1) {
                    int wait = gapMin + rnd.nextInt(Math.max(1, gapMax - gapMin + 1));
                    log("等 " + wait + " 秒…");
                    Thread.sleep(wait * 1000L);
                }
            }

            log("完成: 成功 " + ok + " / 失败 " + fail + " / 跳过 " + skipped);
            writeFile(DIR + "/spark-result.txt",
                    "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())
                    + "\n好友数: " + targets.size() + "\n成功: " + ok + "\n失败: " + fail
                    + "\n文案: " + text + "\n");
            writeFile(DAYF, today);
            report(ok, fail, targets.size(), text);

        } catch (Throwable t) {
            log("每日任务出错: " + t);
            progress("error", String.valueOf(t));
        } finally {
            running = false;
            try { new File(LOCK).delete(); } catch (Throwable ignored) { }
        }
    }

    /** 核心：调用抖音官方 IM 发送通道 */
    private void sendText(ClassLoader cl, String conversationId, String text) {
        progress("step", "开始取 IM client");
        Object client = getClient(cl);
        if (client == null) throw new IllegalStateException("IM SDK 未就绪（取 IM client 失败）");
        progress("step", "client ok: " + client.getClass().getName());

        Object center = XposedHelpers.callMethod(client, M_CENTER);
        progress("step", "center ok");
        Object beanSvc = XposedHelpers.callMethod(client, M_BEANSVC);
        Object builder = XposedHelpers.callMethod(beanSvc, M_NEWBLD);
        progress("step", "builder ok");

        // 诊断模式：把真实方法清单上报，之后靠改云端配置就能调策略，不用重新编译
        if (CFG != null && CFG.optBoolean("diag", false)) {
            dumpMethods("builder", builder.getClass());
            dumpMethods("client", client.getClass());
            dumpMethods("center", center.getClass());
        }

        // 36.8.0：会话查询会死锁（getConversationListModel / getConversation 调用不返回），
        // 改成直接用会话ID字符串设置 —— 30.9.0 时代就是这么发的（builder.LIZ(会话ID)）。
        String sendMode = CFG == null ? "string" : CFG.optString("sendMode", "string");
        if ("conversation".equals(sendMode)) {
            setConversation(client, center, builder, conversationId);
        } else {
            setConversationById(builder, conversationId);
        }
        XposedHelpers.callMethod(builder, M_MSGTYPE, TYPE_TEXT);
        // 内容必须是 JSON（实测 status=2 成功；纯文字反而渲染不出来）
        String content = "{\"text\":" + jsonString(text) + ",\"type\":700}";
        XposedHelpers.callMethod(builder, M_CONTENT, content);
        log("发送: type=" + TYPE_TEXT + "  content=" + content);
        Object msg = XposedHelpers.callMethod(builder, M_BUILD);

        progress("step", "消息已构建，准备发送");
        sendMessage(client, center, msg);
        progress("step", "已提交 sendMessage");

        // 轮询消息状态：2/5 = 成功，3 = 失败；1 = 发送中则继续等（最多 60 秒）
        try {
            int st = -1;
            for (int i = 0; i < 6; i++) {
                Thread.sleep(2000L);
                Object stObj = XposedHelpers.callMethod(msg, "getMsgStatus");
                st = (stObj instanceof Integer) ? (Integer) stObj : -1;
                if (st != 1) break;
            }
            String desc = (st == 2 || st == 5) ? "成功" : (st == 3 ? "失败" : (st == 1 ? "发送中超时(60秒)" : "未知"));
            log("★ 发送结果: status=" + st + " (" + desc + ")");
        } catch (Throwable t) {
            log("读消息状态失败: " + t);
        }
    }

    /** 发送消息：30.9~35.9 走 IMHandlerCenter.sendMessage；40.x 走 SendMsgHandler(IMSdkContext).send */
    private void sendMessage(Object client, Object center, Object msg) {
        java.util.HashMap<String, String> extra = new java.util.HashMap<String, String>();
        try {
            XposedHelpers.callMethod(center, M_SEND, msg, extra);
            log("发送通道: IMHandlerCenter.sendMessage");
            return;
        } catch (Throwable t1) {
            try {
                Object ctx = findImSdkContext(client);
                if (ctx == null) throw new IllegalStateException("取不到 IMSdkContext");
                Class<?> smh = XposedHelpers.findClass(
                        "com.bytedance.im.core.internal.link.handler.sendmsg.SendMsgHandler",
                        client.getClass().getClassLoader());
                Object handler = smh.getConstructor(ctx.getClass()).newInstance(ctx);
                XposedHelpers.callMethod(handler, "send", msg, extra);
                log("发送通道: SendMsgHandler.send");
                return;
            } catch (Throwable t2) {
                throw new IllegalStateException("发送失败: " + t1 + " / " + t2);
            }
        }
    }

    /** 从 IMSdkClient 上找返回 IMSdkContext 的无参方法（40.8 是 LJJIJ） */
    private Object findImSdkContext(Object client) {
        for (java.lang.reflect.Method m : client.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!m.getReturnType().getName().equals("com.bytedance.im.core.mi.IMSdkContext")) continue;
            try { m.setAccessible(true); return m.invoke(client); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 把类的全部方法名+签名分片上报到服务器 */
    private void dumpMethods(String tag, Class<?> clz) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : clz.getDeclaredMethods()) {
                StringBuilder p = new StringBuilder();
                for (Class<?> t : m.getParameterTypes()) {
                    if (p.length() > 0) p.append(",");
                    p.append(t.getSimpleName());
                }
                sb.append(m.getName()).append("(").append(p).append(")->")
                        .append(m.getReturnType().getSimpleName()).append(" ");
            }
            String all = sb.toString();
            int chunk = 1200;
            for (int i = 0; i < all.length(); i += chunk) {
                progress("diag", tag + "[" + (i / chunk) + "]: " + all.substring(i, Math.min(all.length(), i + chunk)));
            }
        } catch (Throwable t) {
            progress("diag", tag + " dump 失败: " + t);
        }
    }

    /** 直接用会话ID字符串设置 builder（避开会死锁的会话模型查询） */
    private void setConversationById(Object builder, String conversationId) {
        for (java.lang.reflect.Method m : builder.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            String n = m.getName();
            boolean looksRight = n.equals("conversation") || n.equals("conversationId")
                    || n.equals("LIZ") || n.equals("setConversation") || n.equals("setConversationId");
            if (!looksRight) continue;
            try {
                m.setAccessible(true);
                m.invoke(builder, conversationId);
                progress("step", "会话ID已设置: " + n);
                return;
            } catch (Throwable ignored) { }
        }
        // 兜底：任意一个 String 入参方法
        for (java.lang.reflect.Method m : builder.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(builder, conversationId);
                progress("step", "会话ID已设置(兜底): " + m.getName());
                return;
            } catch (Throwable ignored) { }
        }
        progress("step", "会话ID设置失败：builder 没有 String 入参方法");
    }

    /** 等会话出现在本地会话模型里（App 刚启动时列表可能还没加载完） */
    private boolean waitConversation(Object client, Object center, String convId, int maxSeconds) {
        for (int i = 0; i < maxSeconds / 3; i++) {
            try {
                Object clm = getConversationListModel(client, center);
                if (clm != null) {
                    Object conv = XposedHelpers.callMethod(clm, M_GETCONV, convId);
                    if (conv != null) {
                        if (i > 0) log("会话已就绪（等了 " + (i * 3) + " 秒）");
                        return true;
                    }
                }
            } catch (Throwable t) {
                if (i == 0) log("查会话出错: " + t);
            }
            try { Thread.sleep(3000L); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    /** 等 IM SDK 就绪（Application.onCreate 时可能尚未初始化），最多等 90 秒
     *  30.9.0：X.0uEM.LIZ.LIZLLL()；35.x+：SDKManager 的静态方法返回 IMSdkClient */
    private Object getClient(ClassLoader cl) {
        for (int i = 0; i < 45; i++) {
            Object client = tryOldClient(cl);
            if (client == null) client = tryNewClient(cl);
            if (client != null) {
                if (i > 0) log("IM SDK 已就绪（等了 " + (i * 2) + " 秒）");
                return client;
            }
            try { Thread.sleep(2000L); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    /** 老路径（30.9.0）：X.0uEM 单例的 LIZLLL() 返回 IIMSdkClient */
    private Object tryOldClient(ClassLoader cl) {
        try {
            Class<?> mgr = XposedHelpers.findClass(CLS_MGR, cl);
            Object mgrInst = XposedHelpers.getStaticObjectField(mgr, FLD_MGR);
            return mgrInst == null ? null : XposedHelpers.callMethod(mgrInst, M_GETCLI);
        } catch (Throwable ignored) { return null; }
    }

    /** 新路径（35.x+）：com.bytedance.ies.im.core.sdk.SDKManager 里返回 IMSdkClient 的静态方法
     *  方法名被混淆（35.9.0 是 LJIIL），所以按返回类型找，不认名字 */
    private Object tryNewClient(ClassLoader cl) {
        try {
            Class<?> mgr = XposedHelpers.findClass("com.bytedance.ies.im.core.sdk.SDKManager", cl);
            for (java.lang.reflect.Method m : mgr.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                String rt = m.getReturnType().getName();
                if (!rt.equals("com.bytedance.im.core.mi.IMSdkClient")
                        && !rt.equals("com.bytedance.im.core.client.mi.IIMSdkClient")) continue;
                try {
                    m.setAccessible(true);
                    Object c = m.invoke(null);
                    if (c != null) { log("走新路径取 IM SDK: " + m.getName()); return c; }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 会话列表模型：35.x 在 IMSdkClient.getIIMSdkModelService() 上，30.9.0 在 IMHandlerCenter 上 */
    private Object getConversationListModel(Object client, Object center) {
        if (center != null) {
            try {
                Object clm = XposedHelpers.callMethod(center, M_GETCLM);
                if (clm != null) return clm;
            } catch (Throwable ignored) { }
        }
        try {
            Object svc = XposedHelpers.callMethod(client, "getIIMSdkModelService");
            if (svc != null) return XposedHelpers.callMethod(svc, M_GETCLM);
        } catch (Throwable ignored) { }
        return null;
    }

    private Object findConversation(Object client, Object center, String convId) {
        Object clm = getConversationListModel(client, center);
        if (clm == null) return null;
        try { return XposedHelpers.callMethod(clm, M_GETCONV, convId); } catch (Throwable t) { return null; }
    }

    /** 30.9.0：builder.LIZ(会话ID)；35.x：builder.conversation(Conversation 对象) */
    private void setConversation(Object client, Object center, Object builder, String conversationId) {
        try {
            Object conv = findConversation(client, center, conversationId);
            if (conv != null) {
                XposedHelpers.callMethod(builder, "conversation", conv);
                return;
            }
        } catch (Throwable ignored) { }
        for (java.lang.reflect.Method m : builder.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            if (m.getReturnType() != builder.getClass()) continue;
            try {
                m.setAccessible(true);
                m.invoke(builder, conversationId);
                return;
            } catch (Throwable ignored) { }
        }
        throw new IllegalStateException("找不到设置会话的方法（版本不兼容）");
    }

    /** 把字符串安全地塞进 JSON（抖音文字消息内容格式为 {"text":"..."}） */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private JSONObject fetchConfig() {
        String body = httpGet(HOST + "/spark/config?token=" + TOKEN);
        if (body == null) return null;
        try { return new JSONObject(body); } catch (Throwable t) { log("配置解析失败: " + t); return null; }
    }

    /** 进度上报：每个阶段都打到服务端，出问题能看出卡在哪一步 */
    private void progress(String stage, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("stage", stage);
            o.put("detail", detail);
            o.put("ts", System.currentTimeMillis());
            httpPost(HOST + "/spark/log?token=" + TOKEN, o.toString());
        } catch (Throwable ignored) { }
    }

    private void report(int ok, int fail, int total, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok); o.put("fail", fail); o.put("total", total); o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            httpPost(HOST + "/spark/log?token=" + TOKEN, o.toString());
        } catch (Throwable ignored) { }
    }

    private static String httpGet(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "spark/7");
            if (c.getResponseCode() != 200) return null;
            return readAll(c);
        } catch (Throwable t) { log("HTTP GET 失败: " + t); return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private static void httpPost(String u, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStreamWriter w = new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8);
            w.write(body); w.flush(); w.close();
            c.getResponseCode();
        } catch (Throwable ignored) { }
        finally { if (c != null) c.disconnect(); }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    /** 当天已成功发送的好友集合。文件坏了/跨天了都当空集处理，绝不因此不发送。 */
    private static Set<String> loadSentToday() {
        Set<String> s = new HashSet<String>();
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
            JSONObject o = new JSONObject(readFile(SENTF).trim());
            if (!today.equals(o.optString("date", ""))) return s;
            JSONArray a = o.optJSONArray("sent");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    String x = a.optString(i, "").trim();
                    if (x.length() > 0) s.add(x);
                }
            }
        } catch (Throwable ignored) { }
        return s;
    }

    /** 每成功发给一个好友就立刻落盘 —— 中途被杀/崩溃后重跑不会重复发。 */
    private static void markSent(String cid) {
        try {
            Set<String> s = loadSentToday();
            s.add(cid);
            JSONArray a = new JSONArray();
            for (String x : s) a.put(x);
            JSONObject o = new JSONObject();
            o.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date()));
            o.put("sent", a);
            writeFile(SENTF, o.toString());
        } catch (Throwable ignored) { }
    }

    private static String readFile(String p) {
        try {
            File f = new File(p);
            if (!f.exists()) return "";
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static JSONObject readJson(String p) {
        String s = readFile(p);
        try { return s.trim().length() == 0 ? new JSONObject() : new JSONObject(s); }
        catch (Throwable t) { return new JSONObject(); }
    }

    private static void writeFile(String p, String s) {
        try {
            FileOutputStream fo = new FileOutputStream(p);
            fo.write(s.getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Throwable t) {
            log("写文件失败 " + p + " -> " + t);
        }
    }

    private void startCollector(ClassLoader cl) {
        if (COLLECTOR_STARTED) { log("采集线程已启动，跳过"); return; }
        COLLECTOR_STARTED = true;
        hookConversationIds(cl);
        startQueueConsumer();
        try { Thread.sleep(90000L); } catch (InterruptedException e) { return; }
        while (true) {
            try {
                Object client = getClient(cl);
                if (client != null) {
                    Object center = XposedHelpers.callMethod(client, M_CENTER);
                    collectOnce(client, center);
                }
            } catch (Throwable t) {
                log("采集出错: " + t);
            }
            try { Thread.sleep(600000L); } catch (InterruptedException e) { return; }
        }
    }

    private void hookConversationIds(ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object r = p.getResult();
                    if (!(r instanceof String)) return;
                    String cid = (String) r;
                    if (cid.isEmpty() || SEEN.contains(cid)) return;
                    enqueue(cid, p.thisObject);
                } catch (Throwable ignored) { }
            }
        };
        XC_MethodHook convHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object conv = p.getResult();
                    if (conv == null) return;
                    Object cidObj = XposedHelpers.getObjectField(conv, "conversationId");
                    if (!(cidObj instanceof String)) return;
                    String cid = (String) cidObj;
                    if (cid.isEmpty() || SEEN.contains(cid)) return;
                    enqueue(cid, conv);
                } catch (Throwable ignored) { }
            }
        };
        for (String cls : new String[]{
                "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo",
                "com.bytedance.im.core.model.Conversation"}) {
            try {
                XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), "getConversationId", hook);
                log("已挂钩 " + cls + ".getConversationId");
            } catch (Throwable t) {
                log("挂钩 " + cls + " 失败: " + t);
            }
        }
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("com.bytedance.im.core.model.ConversationListModel", cl),
                    "getConversation", convHook);
            log("已挂钩 ConversationListModel.getConversation");
        } catch (Throwable t) {
            log("挂钩 ConversationListModel 失败: " + t);
        }
        hookSessionInfo(cl);
    }

    private void hookSessionInfo(ClassLoader cl) {
        XC_MethodHook siHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object si = p.thisObject;
                    if (si == null) return;
                    // ★ 必须读字段，不能调 getConversationId() —— 那正是被钩的方法，会无限递归
                    Object cidObj = XposedHelpers.getObjectField(si, "conversationId");
                    if (!(cidObj instanceof String)) return;
                    String cid = (String) cidObj;
                    if (cid.isEmpty() || SEEN.contains(cid)) return;
                    enqueue(cid, si);
                } catch (Throwable ignored) { }
            }
        };
        Class<?> c;
        try {
            c = XposedHelpers.findClass("com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo", cl);
        } catch (Throwable t) {
            log("找不到 SessionInfo: " + t);
            return;
        }
        int n = 0;
        // 注意：绝不钩 toString —— 框架/日志会反复调用，容易递归崩溃
        for (String m : new String[]{"getSingleChatFromUser", "getSingleChatFromUserId",
                "getSingleChatFromSecUserId", "getTempTitle", "getConversationId"}) {
            try {
                XposedBridge.hookAllMethods(c, m, siHook);
                n++;
            } catch (Throwable ignored) { }
        }
        log("已挂钩 SessionInfo 的 " + n + " 个入口（含 getSingleChatFromUser）");
        hookIMUser(cl);
    }

    private void hookIMUser(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                // 只入队，一次反射都不做 —— IMUser 的方法调用非常频繁
                try {
                    if (p.thisObject != null) enqueue(null, p.thisObject);
                } catch (Throwable ignored) { }
            }
        };
        Class<?> c;
        try {
            c = XposedHelpers.findClass("com.ss.android.ugc.aweme.im.service.model.IMUser", cl);
        } catch (Throwable t) {
            log("找不到 IMUser: " + t);
            return;
        }
        int n = 0;
        for (String m : new String[]{"getNickName", "getRemarkName", "getNickname", "getUserName"}) {
            try {
                XposedBridge.hookAllMethods(c, m, h);
                n++;
            } catch (Throwable ignored) { }
        }
        log("已挂钩 IMUser 的 " + n + " 个昵称入口（uid→会话ID 自动拼接）");
    }

    private static void enqueue(String cid, Object obj) {
        // 消费线程内部的调用（nameOf 会触发被钩的 getNickName）不再入队，否则自激循环
        if (IN_CONSUMER.get() != null) return;
        if (QUEUE.size() > 2000) return;
        QUEUE.add(new Object[]{cid, obj});
    }

    private void startQueueConsumer() {
        if (QUEUE_STARTED) return;
        QUEUE_STARTED = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                IN_CONSUMER.set(Boolean.TRUE);
                while (true) {
                    Object[] it;
                    boolean idle = true;
                    while ((it = QUEUE.poll()) != null) {
                        idle = false;
                        try {
                            String cid = (String) it[0];
                            Object obj = it[1];
                            if (cid == null) {
                                cid = cidOfUser(obj);
                                if (cid == null || SEEN.contains(cid)) continue;
                            }
                            handleCid(cid, obj);
                        } catch (Throwable ignored) { }
                    }
                    if (idle) {
                        try { Thread.sleep(300L); } catch (InterruptedException e) { return; }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        log("采集消费线程已启动（重活已从 UI 线程移出）");
    }

    private static String cidOfUser(Object u) {
        String uid = tryStr(u, "getUid");
        if (uid == null || uid.isEmpty()) uid = tryStr(u, "getUidS");
        if (uid == null || uid.isEmpty() || uid.equals(ME_UID)) return null;
        return singleCid(ME_UID, uid);
    }

    private static String singleCid(String me, String other) {
        try {
            java.math.BigInteger a = new java.math.BigInteger(me);
            java.math.BigInteger b = new java.math.BigInteger(other);
            return a.compareTo(b) <= 0 ? "0:1:" + me + ":" + other : "0:1:" + other + ":" + me;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstNonEmpty(Object o, String... methods) {
        for (String m : methods) {
            String v = tryStr(o, m);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private void handleCid(String cid, Object obj) {
        if (cid == null || cid.isEmpty()) return;
        if (SEEN.contains(cid)) return;
        String name = nameOf(obj);
        if (name == null || name.isEmpty()) {
            Integer n = RETRY.get(cid);
            if (n == null) {
                RETRY.put(cid, 1);
            } else if (n < 5) {
                RETRY.put(cid, n + 1);
            }
            return;
        }
        SEEN.add(cid);
        synchronized (IDS) {
            IDS.put(name, cid);
            PENDING = true;
        }
        log("★ 采集到: " + name + " → " + cid);
    }

    private static void dumpObject(String tag, Object o) {
        if (o == null) { log("  " + tag + " = null"); return; }
        StringBuilder sb = new StringBuilder("  " + tag + " " + o.getClass().getSimpleName() + ": ");
        for (java.lang.reflect.Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String n = m.getName();
            if (!(n.startsWith("get") || n.startsWith("is"))) continue;
            if (n.equals("getClass")) continue;
            try {
                Object v = m.invoke(o);
                if (v == null) continue;
                String s = String.valueOf(v);
                if (s.length() > 70) s = s.substring(0, 70) + "…";
                sb.append(n).append("=").append(s).append("  ");
            } catch (Throwable ignored) { }
        }
        log(sb.toString());
    }

    private void collectOnce(Object client, Object center) {
        Object clm = getConversationListModel(client, center);
        if (clm == null) return;
        Object listObj = XposedHelpers.callMethod(clm, M_GETALL);
        if (listObj instanceof List) {
            for (Object conv : (List<?>) listObj) {
                try {
                    Object cidObj = XposedHelpers.callMethod(conv, "getConversationId");
                    if (!(cidObj instanceof String)) continue;
                    String cid = (String) cidObj;
                    if (cid.isEmpty()) continue;
                    String name = nameOf(conv);
                    if (name == null || name.isEmpty()) continue;
                    synchronized (IDS) {
                        if (!cid.equals(IDS.get(name))) {
                            IDS.put(name, cid);
                            PENDING = true;
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }
        flushIds();
    }

    private void flushIds() {
        if (!PENDING) return;
        JSONObject o = new JSONObject();
        int n = 0;
        synchronized (IDS) {
            for (java.util.Map.Entry<String, String> e : IDS.entrySet()) {
                try { o.put(e.getKey(), e.getValue()); n++; } catch (Throwable ignored) { }
            }
        }
        if (n == 0) return;
        httpPost(HOST + "/spark/ids?token=" + TOKEN, o.toString());
        PENDING = false;
        log("已上报 " + n + " 个会话ID");
    }

    private static String nameOf(Object o) {
        if (o == null) return null;
        for (String m : new String[]{"getName", "getNickName", "getNickname", "getDisplayName",
                "getConversationName", "getTempTitle"}) {
            String v = tryStr(o, m);
            if (v != null && !v.isEmpty()) return v;
        }
        for (String src : new String[]{"getSingleChatFromUser", "getCoreInfo", "getFromUser",
                "getUser", "getChatExt"}) {
            Object sub = tryObj(o, src);
            if (sub == null) continue;
            for (String m : new String[]{"getRemarkName", "getNickName", "getNickname", "getName",
                    "getDisplayName", "getAlias", "getUserName"}) {
                String v = tryStr(sub, m);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        Object members = tryObj(o, "getSingleChatMembers");
        if (members instanceof List) {
            for (Object mb : (List<?>) members) {
                Object user = tryObj(mb, "getUser");
                if (user == null) user = mb;
                for (String m : new String[]{"getRemarkName", "getNickName", "getNickname",
                        "getName", "getDisplayName", "getAlias"}) {
                    String v = tryStr(user, m);
                    if (v != null && !v.isEmpty()) return v;
                }
            }
        }
        // 兜底：取不到昵称就用对方 uid 占位，至少能在配置页勾选（发送按会话ID，不影响功能）
        for (String m : new String[]{"getSingleChatFromUserId", "getUid", "getFromUid"}) {
            String v = tryStr(o, m);
            if (v != null && !v.isEmpty()) return "好友" + v;
        }
        return null;
    }

    private static String tryStr(Object o, String method) {
        try {
            Object r = XposedHelpers.callMethod(o, method);
            if (r instanceof String) return (String) r;
            if (r instanceof Number) return String.valueOf(r);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tryObj(Object o, String method) {
        try {
            return XposedHelpers.callMethod(o, method);
        } catch (Throwable t) {
            return null;
        }
    }
}
