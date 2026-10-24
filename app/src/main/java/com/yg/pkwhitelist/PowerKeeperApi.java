package com.yg.pkwhitelist;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 反射访问 PowerKeeper 自己的配置 API。
 * 类在 PowerKeeper 的 ClassLoader 里，必须用 ctx.getClassLoader() 加载。
 */
public class PowerKeeperApi {
    private static final String TAG = "PKWL";
    public static volatile String lastError = "";

    private static Class<?> load(Context ctx, String name) throws ClassNotFoundException {
        return Class.forName(name, false, ctx.getClassLoader());
    }

    /** 读取当前生效策略（返回内部 camelCase 值） */
    public static String readBgControl(Context ctx, String pkg) {
        String v = readViaManager(ctx, pkg);
        if (v != null) return v;
        return readViaHelper(ctx, pkg);
    }

    /** 首选：直接调 PowerSaveConfigureManager.getPowerSaveAppConfigure(Bundle, Bundle) */
    private static String readViaManager(Context ctx, String pkg) {
        try {
            Class<?> cls = load(ctx, "com.miui.powerkeeper.provider.PowerSaveConfigureManager");
            Method getInstance = cls.getDeclaredMethod("getInstance", Context.class);
            getInstance.setAccessible(true);
            Object mgr = getInstance.invoke(null, ctx);
            if (mgr == null) {
                lastError = "mgr=null";
                return null;
            }
            Method m = cls.getDeclaredMethod("getPowerSaveAppConfigure", Bundle.class, Bundle.class);
            m.setAccessible(true);
            Bundle in = new Bundle();
            in.putString("App", pkg);
            in.putInt("UserId", 0);
            Bundle out = new Bundle();
            m.invoke(mgr, in, out);
            String s = out.getString("AppConfigure");
            if (s == null) {
                lastError = "cfg=null";
                return null;
            }
            return mapBack(s);
        } catch (Throwable t) {
            lastError = "mgr:" + t.getClass().getSimpleName();
            Log.w(TAG, "readViaManager failed " + pkg, t);
            return null;
        }
    }

    /** 兜底：UserConfigureHelper.getBgControl() */
    private static String readViaHelper(Context ctx, String pkg) {
        try {
            Class<?> helper = load(ctx, "com.miui.powerkeeper.provider.UserConfigureHelper");
            Object h = null;
            try {
                Method m = helper.getDeclaredMethod("getUserConfigureHelperByPkg", Context.class, String.class);
                m.setAccessible(true);
                h = m.invoke(null, ctx, pkg);
            } catch (NoSuchMethodException ignore) {
                Method m = helper.getDeclaredMethod("getUserConfigureHelperByPkg", Context.class, int.class, String.class);
                m.setAccessible(true);
                h = m.invoke(null, ctx, 0, pkg);
            }
            if (h == null) {
                lastError = "helper=null";
                return null;
            }
            Method g = helper.getDeclaredMethod("getBgControl");
            g.setAccessible(true);
            Object v = g.invoke(h);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            lastError = "helper:" + t.getClass().getSimpleName();
            Log.w(TAG, "readViaHelper failed " + pkg, t);
            return null;
        }
    }

    private static String mapBack(String s) {
        switch (s) {
            case "no_restrict":
                return "noRestrict";
            case "miui_auto":
                return "miuiAuto";
            case "restrict_bg":
                return "restrictBg";
            case "no_bg":
                return "noBg";
            default:
                return s;
        }
    }

    public static boolean writeBgControl(Context ctx, String pkg, String interfaceValue) {
        try {
            Class<?> mgrCls = load(ctx, "com.miui.powerkeeper.provider.PowerSaveConfigureManager");
            Method getInstance = mgrCls.getDeclaredMethod("getInstance", Context.class);
            getInstance.setAccessible(true);
            Object mgr = getInstance.invoke(null, ctx);
            if (mgr == null) return false;
            Bundle b = new Bundle();
            b.putString("App", pkg);
            b.putString("AppConfigure", interfaceValue);
            Method set = mgrCls.getDeclaredMethod("setPowerSaveAppConfigure", Bundle.class);
            set.setAccessible(true);
            Object r = set.invoke(mgr, b);
            return r instanceof Integer && (Integer) r == 0;
        } catch (Throwable t) {
            lastError = "write:" + t.getClass().getSimpleName();
            Log.w(TAG, "writeBgControl failed " + pkg, t);
            return false;
        }
    }

    public static String interfaceValue(int mode) {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                return "no_restrict";
            case Bridge.MODE_DENY:
                return "restrict_bg";
            default:
                return "miui_auto";
        }
    }

    public static String internalValue(int mode) {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                return "noRestrict";
            case Bridge.MODE_DENY:
                return "restrictBg";
            default:
                return "miuiAuto";
        }
    }
}
