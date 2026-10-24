package com.yg.pkwhitelist;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 运行在 com.miui.powerkeeper 进程（android.uid.system）里。
 *
 * 负责三件事：
 *  1. 拦截 PowerKeeper 往系统 Doze 白名单写名单（addPowerSaveWhitelistApps）；
 *  2. 伪造 isIgnoringBatteryOptimizations 的结果，解掉"智能被吸成无限制"；
 *  3. 注册广播桥，给模块 UI 提供查询与控制能力。
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "PKWL";

    private static Context sCtx;
    private static OverrideStore sStore;
    private static DeviceIdleApi sIdle;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Bridge.PKG.equals(lp.packageName)) return;
        if (lp.processName != null && !lp.processName.equals(Bridge.PKG)) return;

        try {
            sCtx = AndroidAppHelper.currentApplication();
        } catch (Throwable ignore) {
        }
        if (sCtx == null) {
            XposedBridge.log(TAG + ": no context, skip");
            return;
        }
        sStore = new OverrideStore(sCtx);
        sIdle = new DeviceIdleApi();

        hookAddWhitelist(lp);
        hookBatteryOptimization();
        hookGetConfigure(lp);
        hookSetConfigure(lp);
        registerBridge();

        XposedBridge.log(TAG + ": hooks installed, idle=" + sIdle.available());
    }

    // ---------- hooks ----------

    private void hookAddWhitelist(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.powerkeeper.utils.CommonAdapter", lp.classLoader,
                    "addPowerSaveWhitelistApps", "android.os.IDeviceIdleController", String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String[] in = (String[]) param.args[1];
                            param.args[1] = filter(in, true);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook addPowerSaveWhitelistApps failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.powerkeeper.utils.CommonAdapter", lp.classLoader,
                    "removePowerSaveWhitelistApps", "android.os.IDeviceIdleController", String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String[] in = (String[]) param.args[1];
                            param.args[1] = filter(in, true);
                        }
                    });
        } catch (Throwable ignore) {
        }
    }

    private void hookBatteryOptimization() {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.PowerManager", null,
                    "isIgnoringBatteryOptimizations", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String pkg = (String) param.args[0];
                            if (pkg == null) return;
                            int mode = sStore.get(pkg);
                            if (mode == Bridge.MODE_ALLOW) param.setResult(true);
                            else if (mode == Bridge.MODE_DENY) param.setResult(false);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook isIgnoringBatteryOptimizations failed: " + t);
        }
    }

    private void hookGetConfigure(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.powerkeeper.provider.PowerSaveConfigureManager", lp.classLoader,
                    "getPowerSaveAppConfigure", Bundle.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Bundle in = (Bundle) param.args[0];
                            Bundle out = (Bundle) param.args[1];
                            if (in == null || out == null) return;
                            String pkg = in.getString("App");
                            if (pkg == null) return;
                            int mode = sStore.get(pkg);
                            if (mode != Bridge.MODE_FOLLOW) {
                                out.putString("AppConfigure", PowerKeeperApi.interfaceValue(mode));
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getPowerSaveAppConfigure failed: " + t);
        }
    }

    private void hookSetConfigure(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.miui.powerkeeper.provider.PowerSaveConfigureManager", lp.classLoader,
                    "setPowerSaveAppConfigure", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Bundle b = (Bundle) param.args[0];
                            if (b == null) return;
                            String pkg = b.getString("App");
                            if (pkg == null) return;
                            int mode = sStore.get(pkg);
                            if (mode != Bridge.MODE_FOLLOW) {
                                b.putString("AppConfigure", PowerKeeperApi.interfaceValue(mode));
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setPowerSaveAppConfigure failed: " + t);
        }
    }

    /** 按 override 表过滤名单：DENY 剔除，ALLOW 补齐 */
    private String[] filter(String[] in, boolean allowAdd) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String p : in) {
                if (p == null) continue;
                if (sStore.get(p) == Bridge.MODE_DENY) continue;
                out.add(p);
            }
        }
        if (allowAdd) {
            for (Map.Entry<String, Integer> e : sStore.all().entrySet()) {
                if (e.getValue() == Bridge.MODE_ALLOW && !out.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        return out.toArray(new String[0]);
    }

    // ---------- bridge ----------

    private void registerBridge() {
        IntentFilter f = new IntentFilter();
        f.addAction(Bridge.ACTION_QUERY);
        f.addAction(Bridge.ACTION_SET);
        f.addAction(Bridge.ACTION_APPLY_ALL);
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    handle(this, intent);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": bridge error " + t);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sCtx.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
            } else {
                sCtx.registerReceiver(rcv, f);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerReceiver failed: " + t);
        }
    }

    private void handle(BroadcastReceiver r, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (Bridge.ACTION_QUERY.equals(action)) {
            ArrayList<String> pkgs = intent.getStringArrayListExtra(Bridge.EXTRA_PKGS);
            ArrayList<Bundle> out = new ArrayList<>();
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    Bundle b = statusOf(pkg);
                    if (b != null) out.add(b);
                }
            }
            Bundle res = new Bundle();
            res.putParcelableArrayList(Bridge.EXTRA_RESULT, out);
            resultExtras(r, res);
        } else if (Bridge.ACTION_SET.equals(action)) {
            String pkg = intent.getStringExtra(Bridge.EXTRA_PKG);
            int mode = intent.getIntExtra(Bridge.EXTRA_MODE, Bridge.MODE_FOLLOW);
            if (pkg != null) {
                sStore.set(pkg, mode);
                apply(pkg, mode);
            }
            resultCode(r, Activity.RESULT_OK);
        } else if (Bridge.ACTION_APPLY_ALL.equals(action)) {
            for (Map.Entry<String, Integer> e : sStore.all().entrySet()) {
                apply(e.getKey(), e.getValue());
            }
            resultCode(r, Activity.RESULT_OK);
        }
    }

    /** BroadcastReceiver.setResultExtras 是 @hide API，编译期不可见，只能反射 */
    private static void resultExtras(BroadcastReceiver r, Bundle b) {
        try {
            Method m = BroadcastReceiver.class.getDeclaredMethod("setResultExtras", Bundle.class);
            m.setAccessible(true);
            m.invoke(r, b);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setResultExtras failed " + t);
        }
    }

    private static void resultCode(BroadcastReceiver r, int code) {
        try {
            Method m = BroadcastReceiver.class.getDeclaredMethod("setResultCode", int.class);
            m.setAccessible(true);
            m.invoke(r, code);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setResultCode failed " + t);
        }
    }

    private Bundle statusOf(String pkg) {
        try {
            PackageManager pm = sCtx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Bundle b = new Bundle();
            b.putString(Bridge.KEY_PKG, pkg);
            b.putString(Bridge.KEY_LABEL, String.valueOf(ai.loadLabel(pm)));
            b.putInt(Bridge.KEY_UID, ai.uid);
            b.putBoolean(Bridge.KEY_SYSTEM, (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            b.putBoolean(Bridge.KEY_DOZE, sIdle.isWhitelisted(pkg, 0));
            String bg = PowerKeeperApi.readBgControl(sCtx, pkg);
            b.putString(Bridge.KEY_BG, bg == null ? "" : bg);
            b.putInt(Bridge.KEY_MODE, sStore.get(pkg));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void apply(String pkg, int mode) {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                sIdle.add(pkg);
                PowerKeeperApi.writeBgControl(sCtx, pkg, "no_restrict");
                break;
            case Bridge.MODE_DENY:
                sIdle.remove(pkg);
                PowerKeeperApi.writeBgControl(sCtx, pkg, "restrict_bg");
                break;
            default:
                PowerKeeperApi.writeBgControl(sCtx, pkg, "miui_auto");
                break;
        }
    }
}
