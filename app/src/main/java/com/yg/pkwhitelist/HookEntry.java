package com.yg.pkwhitelist;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口，运行在 com.miui.powerkeeper 进程（android.uid.system）。
 */
public class HookEntry extends XposedModule {
    private static final String TAG = "PKWL";

    private volatile Context ctx;
    private volatile OverrideStore store;
    private volatile DeviceIdleApi idle;
    private volatile String diag = "hook=installing";
    private volatile String lastError = "";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logi("loaded in " + param.getProcessName() + " api=" + getApiVersion()
                + " fw=" + safeFramework());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!Bridge.PKG.equals(param.getPackageName())) return;
        try {
            installHooks(param.getDefaultClassLoader());
        } catch (Throwable t) {
            fail("install", t);
        }
        startBridge();
    }

    private String safeFramework() {
        try {
            return getFrameworkName() + "/" + getFrameworkVersion();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void fail(String where, Throwable t) {
        lastError = where + ": " + t;
        diag = "hook=failed " + lastError;
        logi("" + lastError);
    }

    // ---------- hooks ----------

    private void installHooks(ClassLoader cl) {
        hookAddRemove(cl);
        hookBatteryOptimization();
        hookGetConfigure(cl);
        hookSetConfigure(cl);
        diag = "hook=ok api=" + getApiVersion();
    }

    private void hookAddRemove(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.miui.powerkeeper.utils.CommonAdapter", false, cl);
            Method add = c.getDeclaredMethod("addPowerSaveWhitelistApps",
                    Class.forName("android.os.IDeviceIdleController"), String[].class);
            hook(add).setId("pk-add").intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                String[] filtered = filterForAdd((String[]) args[1]);
                // 全部被拒（例如只有 DENY 包）时连 IPC 都不发，避免系统侧再记一次
                if (filtered.length == 0) return null;
                args[1] = filtered;
                return chain.proceed(args);
            });
            try {
                Method rm = c.getDeclaredMethod("removePowerSaveWhitelistApps",
                        Class.forName("android.os.IDeviceIdleController"), String[].class);
                hook(rm).setId("pk-rm").intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    args[1] = filterForRemove((String[]) args[1]);
                    return chain.proceed(args);
                });
            } catch (NoSuchMethodException ignore) {
            }
        } catch (Throwable t) {
            fail("hookAddWhitelist", t);
        }
    }

    private void hookBatteryOptimization() {
        try {
            Method m = Class.forName("android.os.PowerManager")
                    .getDeclaredMethod("isIgnoringBatteryOptimizations", String.class);
            hook(m).setId("pk-ignore-batt").intercept(chain -> {
                String pkg = (String) chain.getArg(0);
                Object origin = chain.proceed();
                if (pkg == null) return origin;
                int mode = modeOf(pkg);
                if (mode == Bridge.MODE_ALLOW) return Boolean.TRUE;
                if (mode == Bridge.MODE_DENY) return Boolean.FALSE;
                return origin;
            });
        } catch (Throwable t) {
            fail("hookBatteryOptimization", t);
        }
    }

    private void hookGetConfigure(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.miui.powerkeeper.provider.PowerSaveConfigureManager", false, cl);
            Method m = c.getDeclaredMethod("getPowerSaveAppConfigure", Bundle.class, Bundle.class);
            hook(m).setId("pk-get-cfg").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Bundle in = (Bundle) chain.getArg(0);
                    Bundle out = (Bundle) chain.getArg(1);
                    if (in != null && out != null) {
                        String pkg = in.getString("App");
                        int mode = modeOf(pkg);
                        // 先取系统算出来的原值，再决定要不要覆盖
                        String original = out.getString("AppConfigure");
                        if (mode != Bridge.MODE_FOLLOW) {
                            out.putString("AppConfigure", PowerKeeperApi.interfaceValue(mode));
                        }
                        if (mode == Bridge.MODE_DENY && pkg != null) {
                            // 原值为空（没这行 / -1）或被自动回写成 no_restrict 时，重新压一次
                            if (original == null || "no_restrict".equals(original)) {
                                scheduleReEnforce(pkg);
                            }
                        }
                    }
                } catch (Throwable ignore) {
                }
                return r;
            });
        } catch (Throwable t) {
            fail("hookGetConfigure", t);
        }
    }

    private final java.util.Map<String, Long> lastEnforce = new java.util.concurrent.ConcurrentHashMap<>();

    /** 限频自愈：同一包 30 秒内只重新强制一次，避免读写回环 */
    private void scheduleReEnforce(String pkg) {
        Long prev = lastEnforce.get(pkg);
        long now = System.currentTimeMillis();
        if (prev != null && now - prev < 30_000L) return;
        lastEnforce.put(pkg, now);
        Thread t = new Thread(() -> {
            try {
                String r = PowerKeeperApi.enforce(ctx, pkg, idle);
                purgeAlwaysWhite();
                diag = "re-enforce " + pkg + " " + r;
                logi(diag);
            } catch (Throwable ignore) {
            }
        }, "pk-heal");
        t.setDaemon(true);
        t.start();
    }

    private void hookSetConfigure(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.miui.powerkeeper.provider.PowerSaveConfigureManager", false, cl);
            Method m = c.getDeclaredMethod("setPowerSaveAppConfigure", Bundle.class);
            hook(m).setId("pk-set-cfg").intercept(chain -> {
                try {
                    Bundle b = (Bundle) chain.getArg(0);
                    if (b != null) {
                        String pkg = b.getString("App");
                        int mode = modeOf(pkg);
                        if (mode != Bridge.MODE_FOLLOW) {
                            b.putString("AppConfigure", PowerKeeperApi.interfaceValue(mode));
                        }
                    }
                } catch (Throwable ignore) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            fail("hookSetConfigure", t);
        }
    }

    private int modeOf(String pkg) {
        if (pkg == null || store == null) return Bridge.MODE_FOLLOW;
        return store.get(pkg);
    }

    /** 加白名单：剔除 DENY，补齐 ALLOW */
    private String[] filterForAdd(String[] in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String p : in) {
                if (p == null) continue;
                if (modeOf(p) == Bridge.MODE_DENY) continue;
                out.add(p);
            }
        }
        if (store != null) {
            for (Map.Entry<String, Integer> e : store.all().entrySet()) {
                if (e.getValue() == Bridge.MODE_ALLOW && !out.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /** 删白名单：剔除 ALLOW（别删它），保留 DENY（确保删掉） */
    private String[] filterForRemove(String[] in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String p : in) {
                if (p == null) continue;
                if (modeOf(p) == Bridge.MODE_ALLOW) continue;
                out.add(p);
            }
        }
        if (store != null) {
            for (Map.Entry<String, Integer> e : store.all().entrySet()) {
                if (e.getValue() == Bridge.MODE_DENY && !out.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        return out.toArray(new String[0]);
    }

    // ---------- bridge ----------

    private void startBridge() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                Context c = currentApp();
                if (c != null) {
                    try {
                        ctx = c;
                        store = new OverrideStore(c);
                        idle = new DeviceIdleApi();
                        diag = "hook=ok idle=" + idle.available() + " api=" + getApiVersion();
                        register(c);
                        logi("bridge ready: " + diag);
                        purgeLater();
                        return;
                    } catch (Throwable e) {
                        fail("bridge", e);
                        return;
                    }
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignore) {
                    return;
                }
            }
            diag = "hook=ok ctx=null";
            logi("context not ready");
        }, "pk-bridge");
        t.setDaemon(true);
        t.start();
    }

    /** DeviceIdleController 构造时才会填充 sAlwaysWhiteApps，等它跑完再踢 */
    private void purgeLater() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignore) {
                return;
            }
            purgeAlwaysWhite();
        }, "pk-purge");
        t.setDaemon(true);
        t.start();
    }

    private static Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private void register(Context c) {
        IntentFilter f = new IntentFilter();
        f.addAction(Bridge.ACTION_QUERY);
        f.addAction(Bridge.ACTION_SET);
        f.addAction(Bridge.ACTION_APPLY_ALL);
        f.addAction(Bridge.ACTION_CLEAR);
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    handle(this, intent);
                } catch (Throwable t) {
                    logi("bridge error " + t);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
        } else {
            c.registerReceiver(rcv, f);
        }
        diag = diag + " | bridge=ok";
        publishStatus(c, diag);
        logi(diag);
    }

    /** 一次性把 userTable 全部读进内存，避免逐个应用查库（190 次查询会慢到超时） */
    private volatile Map<String, String> bgMap;

    private void refreshBgMap() {
        Map<String, String> m = new java.util.HashMap<>();
        try {
            Class<?> helper = Class.forName("com.miui.powerkeeper.provider.UserConfigureHelper",
                    false, ctx.getClassLoader());
            Method create = helper.getDeclaredMethod("createFromTable", Context.class);
            create.setAccessible(true);
            Object list = create.invoke(null, ctx);
            Method gp = helper.getDeclaredMethod("getPackageName");
            Method gb = helper.getDeclaredMethod("getBgControl");
            gp.setAccessible(true);
            gb.setAccessible(true);
            if (list instanceof List) {
                for (Object h : (List<?>) list) {
                    Object p = gp.invoke(h);
                    if (!(p instanceof String)) continue;
                    Object b = gb.invoke(h);
                    if (b instanceof String) m.put((String) p, (String) b);
                }
            }
        } catch (Throwable t) {
            logi("refreshBgMap " + t);
        }
        bgMap = m;
        logi("bgMap size=" + m.size());
    }

    /** 把 hook 存活状态写到 Settings.Global，UI 用来区分「hook 没加载」和「通道没通」 */
    private void publishStatus(Context c, String s) {
        try {
            android.provider.Settings.Global.putString(c.getContentResolver(),
                    Bridge.GK_STATUS, s + " @" + System.currentTimeMillis());
        } catch (Throwable ignore) {
        }
    }

    private void handle(BroadcastReceiver r, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (Bridge.ACTION_QUERY.equals(action)) {
            ArrayList<String> pkgs = intent.getStringArrayListExtra(Bridge.EXTRA_PKGS);
            ArrayList<Bundle> out = new ArrayList<>();
            // 省电模式切换时会把 sPowerSaveModeAlwaysWhiteApps 并回常驻集合，顺手再踢一次
            purgeAlwaysWhite();
            refreshBgMap();
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    Bundle b = statusOf(pkg);
                    if (b != null) out.add(b);
                }
            }
            Bundle res = new Bundle();
            res.putParcelableArrayList(Bridge.EXTRA_RESULT, out);
            String d = diag;
            if (!PowerKeeperApi.lastError.isEmpty()) d += " | bg:" + PowerKeeperApi.lastError;
            if (!DeviceIdleApi.lastError.isEmpty()) d += " | idle:" + DeviceIdleApi.lastError;
            res.putString(Bridge.KEY_DIAG, d);
            publishStatus(ctx, "q=" + (pkgs == null ? 0 : pkgs.size()));
            resultExtras(r, res);
        } else if (Bridge.ACTION_SET.equals(action)) {
            String pkg = intent.getStringExtra(Bridge.EXTRA_PKG);
            int mode = intent.getIntExtra(Bridge.EXTRA_MODE, Bridge.MODE_FOLLOW);
            if (pkg != null && store != null) {
                int old = store.get(pkg);
                store.set(pkg, mode);
                // 只有从「跟随」第一次切过去时才抓快照，避免把已污染的状态当成原始值
                apply(pkg, mode, old == Bridge.MODE_FOLLOW);
            }
            resultCode(r, Activity.RESULT_OK);
        } else if (Bridge.ACTION_APPLY_ALL.equals(action)) {
            if (store != null) {
                for (Map.Entry<String, Integer> e : store.all().entrySet()) {
                    apply(e.getKey(), e.getValue(), false);
                }
            }
            resultCode(r, Activity.RESULT_OK);
        } else if (Bridge.ACTION_CLEAR.equals(action)) {
            String report = clearAll();
            Bundle res = new Bundle();
            res.putString(Bridge.KEY_REPORT, report);
            res.putString(Bridge.KEY_DIAG, diag);
            resultExtras(r, res);
            resultCode(r, Activity.RESULT_OK);
        }
    }

    /** libxposed API 102: log(int priority, String tag, String msg) */
    private void logi(String msg) {
        try {
            log(4, TAG, msg);
        } catch (Throwable ignore) {
        }
    }

    private static void resultExtras(BroadcastReceiver r, Bundle b) {
        try {
            java.lang.reflect.Method m = BroadcastReceiver.class.getDeclaredMethod("setResultExtras", Bundle.class);
            m.setAccessible(true);
            m.invoke(r, b);
        } catch (Throwable ignore) {
        }
    }

    private static void resultCode(BroadcastReceiver r, int code) {
        try {
            java.lang.reflect.Method m = BroadcastReceiver.class.getDeclaredMethod("setResultCode", int.class);
            m.setAccessible(true);
            m.invoke(r, code);
        } catch (Throwable ignore) {
        }
    }

    private Bundle statusOf(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Bundle b = new Bundle();
            b.putString(Bridge.KEY_PKG, pkg);
            b.putString(Bridge.KEY_LABEL, String.valueOf(ai.loadLabel(pm)));
            b.putInt(Bridge.KEY_UID, ai.uid);
            b.putBoolean(Bridge.KEY_SYSTEM, (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            b.putBoolean(Bridge.KEY_DOZE, idle != null && idle.isWhitelisted(pkg, 0));
            String bg = bgMap != null ? bgMap.get(pkg) : null;
            if (bg == null) {
                // 表里没有这行，才退回代价高的单查
                bg = PowerKeeperApi.readBgControl(ctx, pkg);
            }
            int mode = modeOf(pkg);
            // 强制禁止时不让被系统回写的脏值影响显示
            if (mode == Bridge.MODE_DENY) bg = "miuiAuto";
            b.putString(Bridge.KEY_BG, bg == null ? "" : bg);
            b.putInt(Bridge.KEY_MODE, mode);
            b.putBoolean(Bridge.KEY_LAUNCHER, isLauncher(pkg));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void apply(String pkg, int mode, boolean captureOrig) {
        try {
            switch (mode) {
                case Bridge.MODE_ALLOW:
                    if (idle != null) idle.add(pkg);
                    PowerKeeperApi.writeBgControl(ctx, pkg, "no_restrict");
                    break;
                case Bridge.MODE_DENY: {
                    // 先抓原始状态，清除时才能还原
                    lastEnforce.put(pkg, System.currentTimeMillis());
                    boolean wasDoze = idle != null && idle.isWhitelisted(pkg, 0);
                    String wasBg = PowerKeeperApi.readBgControl(ctx, pkg);
                    boolean launcher = isLauncher(pkg);
                    // 智能限制 + 清零一切白名单豁免：补行 -> 移出 doze -> 写 miui_auto
                    String row = PowerKeeperApi.ensureRow(ctx, pkg);
                    if (store != null && captureOrig) {
                        store.saveOrigIfAbsent(pkg, wasDoze, wasBg, PowerKeeperApi.wasInserted(row));
                    }
                    // 桌面不能踢出 doze：被 Doze 杀掉会重载桌面，还会弹一次系统设置页
                    boolean rm = !launcher && idle != null && idle.removeForce(pkg);
                    boolean w = PowerKeeperApi.writeBgControl(ctx, pkg, "miui_auto");
                    if (!launcher) purgeAlwaysWhite();
                    // 核心系统应用（紧急警报/急救信息）在系统白名单里，删不掉也压不住，
                    // PowerKeeper 会异步把配置回写，这里补两次
                    if (!launcher) scheduleRewrite(pkg);
                    diag = "enforce " + pkg + (launcher ? " [launcher]" : "")
                            + " row[" + row + "] rm=" + rm + " write=" + w
                            + " orig=" + (wasDoze ? "D1" : "D0") + "/" + wasBg;
                    logi(diag);
                    break;
                }
                default:
                    PowerKeeperApi.writeBgControl(ctx, pkg, "miui_auto");
                    break;
            }
        } catch (Throwable t) {
            logi("apply failed " + t);
        }
    }

    private final Map<String, Boolean> launcherCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 桌面（CATEGORY_HOME）单独处理：踢出 doze 白名单会导致桌面重载 + 弹一次系统页 */
    private boolean isLauncher(String pkg) {
        Boolean c = launcherCache.get(pkg);
        if (c != null) return c;
        boolean r = false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            List<android.content.pm.ResolveInfo> list = ctx.getPackageManager().queryIntentActivities(i, 0);
            if (list != null) {
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                        r = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        launcherCache.put(pkg, r);
        return r;
    }

    /** 核心系统应用的配置会被 PowerKeeper 异步回写，隔一会儿补两刀 */
    private void scheduleRewrite(String pkg) {
        Thread t = new Thread(() -> {
            for (int delay : new int[]{300, 1200}) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                if (store == null || store.get(pkg) != Bridge.MODE_DENY) return;
                PowerKeeperApi.writeBgControl(ctx, pkg, "miui_auto");
            }
        }, "pk-rewrite");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 微信/QQ 这类包还在 arrays.xml 的 doze_always_white_apps 里，
     * 走 DeviceIdleController.sAlwaysWhiteApps（不进系统白名单，而是 Doze 临时豁免），
     * 改 local.config 删不掉。这里直接把标记为 DENY 的包从两个常驻集合里踢出去；
     * 集合只在 sAlwaysWhiteAppsInited 之前填充一次，所以踢一次就够。
     */
    private void purgeAlwaysWhite() {
        Context c = ctx;
        if (c == null || store == null) return;
        try {
            Class<?> dc = Class.forName("com.miui.powerkeeper.controller.DeviceIdleController",
                    false, c.getClassLoader());
            purgeSet(dc, "sAlwaysWhiteApps");
            purgeSet(dc, "sPowerSaveModeAlwaysWhiteApps");
        } catch (Throwable t) {
            logi("purgeAlwaysWhite " + t);
        }
    }

    private void purgeSet(Class<?> owner, String field) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(null);
            if (!(v instanceof java.util.Set)) return;
            java.util.Set<?> set = (java.util.Set<?>) v;
            List<String> drop = new ArrayList<>();
            for (Object o : set) {
                if (o instanceof String && modeOf((String) o) == Bridge.MODE_DENY) {
                    drop.add((String) o);
                }
            }
            if (!drop.isEmpty() && set.removeAll(drop)) {
                if (store != null) {
                    for (String p : drop) store.markPurged(p);
                }
                logi("purged " + field + " " + drop);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 一键清除：把动过的东西全部还原到第一次强制之前的状态。
     *  - doze 白名单：按快照恢复（原本在就加回，原本不在就移除）
     *  - bgControl：恢复快照里的原值，没有快照的退回 miuiAuto
     *  - 由我们插入的 userTable 行：删除
     *  - 被踢出 sAlwaysWhiteApps 的包：加回去
     *  - SharedPreferences：整个清空
     */
    private String clearAll() {
        if (store == null || ctx == null || idle == null) return "store=null";
        Map<String, Integer> all = store.all();
        int total = 0, restored = 0, rowsDeleted = 0, alwaysBack = 0;
        StringBuilder err = new StringBuilder();
        for (String pkg : all.keySet()) {
            total++;
            String snap = store.orig(pkg);
            if (snap == null) {
                PowerKeeperApi.writeBgControl(ctx, pkg, "miui_auto");
                continue;
            }
            boolean wasDoze = snap.contains("D1");
            String wasBg = between(snap, "|B", "|I");
            try {
                if (wasDoze) {
                    idle.add(pkg);
                } else {
                    idle.removeForce(pkg);
                }
                PowerKeeperApi.writeBgControl(ctx, pkg, PowerKeeperApi.toInterface(wasBg));
                if (snap.endsWith("|I1")) {
                    String d = PowerKeeperApi.deleteRow(ctx, pkg);
                    if (d.startsWith("del=1")) rowsDeleted++;
                }
                restored++;
            } catch (Throwable t) {
                err.append(pkg).append(':').append(t.getClass().getSimpleName()).append(' ');
            }
        }
        alwaysBack = restoreAlwaysWhite(store.purged());
        store.wipe();
        diag = "cleared=" + total + " restored=" + restored
                + " rows=" + rowsDeleted + " alwaysBack=" + alwaysBack
                + (err.length() > 0 ? " err=" + err : "");
        logi(diag);
        return diag;
    }

    /** 把之前被 purge 掉的包加回 sAlwaysWhiteApps */
    private int restoreAlwaysWhite(java.util.Set<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return 0;
        int n = 0;
        try {
            Class<?> dc = Class.forName("com.miui.powerkeeper.controller.DeviceIdleController",
                    false, ctx.getClassLoader());
            java.lang.reflect.Field f = dc.getDeclaredField("sAlwaysWhiteApps");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof java.util.Set) {
                java.util.Set<String> set = (java.util.Set<String>) v;
                for (String p : pkgs) {
                    if (set.add(p)) n++;
                }
            }
        } catch (Throwable t) {
            logi("restoreAlwaysWhite " + t);
        }
        return n;
    }

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return null;
        a += start.length();
        int b = s.indexOf(end, a);
        if (b < 0) b = s.length();
        return s.substring(a, b);
    }
}
