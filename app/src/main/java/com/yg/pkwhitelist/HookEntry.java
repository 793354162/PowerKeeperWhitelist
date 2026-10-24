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
                args[1] = filter((String[]) args[1]);
                return chain.proceed(args);
            });
            try {
                Method rm = c.getDeclaredMethod("removePowerSaveWhitelistApps",
                        Class.forName("android.os.IDeviceIdleController"), String[].class);
                hook(rm).setId("pk-rm").intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    args[1] = filter((String[]) args[1]);
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
                        if (mode != Bridge.MODE_FOLLOW) {
                            out.putString("AppConfigure", PowerKeeperApi.interfaceValue(mode));
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

    private String[] filter(String[] in) {
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
                        register(c);
                        diag = "hook=ok idle=" + idle.available() + " api=" + getApiVersion();
                        logi("bridge ready, idle=" + idle.available());
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
            res.putString(Bridge.KEY_DIAG, diag + (lastError.isEmpty() ? "" : " | " + lastError));
            resultExtras(r, res);
        } else if (Bridge.ACTION_SET.equals(action)) {
            String pkg = intent.getStringExtra(Bridge.EXTRA_PKG);
            int mode = intent.getIntExtra(Bridge.EXTRA_MODE, Bridge.MODE_FOLLOW);
            if (pkg != null && store != null) {
                store.set(pkg, mode);
                apply(pkg, mode);
            }
            resultCode(r, Activity.RESULT_OK);
        } else if (Bridge.ACTION_APPLY_ALL.equals(action)) {
            if (store != null) {
                for (Map.Entry<String, Integer> e : store.all().entrySet()) {
                    apply(e.getKey(), e.getValue());
                }
            }
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
            String bg = PowerKeeperApi.readBgControl(ctx, pkg);
            b.putString(Bridge.KEY_BG, bg == null ? "" : bg);
            b.putInt(Bridge.KEY_MODE, modeOf(pkg));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void apply(String pkg, int mode) {
        try {
            switch (mode) {
                case Bridge.MODE_ALLOW:
                    if (idle != null) idle.add(pkg);
                    PowerKeeperApi.writeBgControl(ctx, pkg, "no_restrict");
                    break;
                case Bridge.MODE_DENY:
                    if (idle != null) idle.remove(pkg);
                    PowerKeeperApi.writeBgControl(ctx, pkg, "restrict_bg");
                    break;
                default:
                    PowerKeeperApi.writeBgControl(ctx, pkg, "miui_auto");
                    break;
            }
        } catch (Throwable t) {
            logi("apply failed " + t);
        }
    }
}
