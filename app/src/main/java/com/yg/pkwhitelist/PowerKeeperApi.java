package com.yg.pkwhitelist;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 反射访问 PowerKeeper 自己的配置 API（UserConfigureHelper / PowerSaveConfigureManager）。
 * 只在 hook 侧使用。
 *
 * 注意：这些类在 PowerKeeper 的 ClassLoader 里，必须用 ctx.getClassLoader() 加载，
 * 不能用模块自己的 Class.forName。
 */
public class PowerKeeperApi {
    private static final String TAG = "PKWL";

    private static Class<?> load(Context ctx, String name) throws ClassNotFoundException {
        return Class.forName(name, false, ctx.getClassLoader());
    }

    /** 读取内部 bgControl 值：noRestrict / miuiAuto / restrictBg / noBg */
    public static String readBgControl(Context ctx, String pkg) {
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
            if (h == null) return null;
            Method g = helper.getDeclaredMethod("getBgControl");
            g.setAccessible(true);
            Object v = g.invoke(h);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            Log.w(TAG, "readBgControl failed: " + pkg, t);
            return null;
        }
    }

    /** 通过 PowerKeeper 的设置入口写 bgControl（界面值用 snake_case） */
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
            Log.w(TAG, "writeBgControl failed: " + pkg, t);
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
