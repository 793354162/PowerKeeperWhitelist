package com.yg.pkwhitelist;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 反射访问 PowerKeeper 自己的配置 API。
 * 类在 PowerKeeper 的 ClassLoader 里，必须用 ctx.getClassLoader() 加载。
 *
 * 关键背景（来自 PowerKeeper smali）：
 *  - PowerSaveConfigureManager.setPowerSaveAppConfigure 只写 userTable，不碰 doze 白名单；
 *  - 它在 getId() < 0（没这行）时直接 return -1，所以必须先把行补出来才能写；
 *  - getPowerSaveAppConfigure 有自动回写：miuiAuto + 在 doze 白名单 -> no_restrict。
 *    所以强制禁止必须「先移出白名单，再写 miui_auto」。
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

    /** userTable 的 ContentUri（UserConfigure.CONTENT_URI = PowerKeeperConfigure.CONTENT_URI + "/userTable"） */
    private static Uri userTable(Context ctx) throws Throwable {
        Class<?> uc = load(ctx, "com.miui.powerkeeper.provider.UserConfigure");
        Object v = uc.getField("CONTENT_URI").get(null);
        return v instanceof Uri ? (Uri) v : null;
    }

    /**
     * A 方案：确保 userTable 里有这个包的一行。
     * 没有就 insert（userId/pkgName/bgControl=miuiAuto），有但 bgControl 为空就补值。
     * 返回一句诊断，便于在 UI 的 diag 里核对。
     */
    public static String ensureRow(Context ctx, String pkg) {
        Cursor c = null;
        try {
            Uri uri = userTable(ctx);
            if (uri == null) return "uri=null";
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(uri, new String[]{"_id", "bgControl"},
                    "userId = ? AND pkgName = ?", new String[]{"0", pkg}, null);
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                String bg = c.isNull(1) ? null : c.getString(1);
                if (bg == null) {
                    ContentValues cv = new ContentValues();
                    cv.put("bgControl", "miuiAuto");
                    int n = cr.update(ContentUris.withAppendedId(uri, id), cv, null, null);
                    return "row=" + id + " filled=" + n;
                }
                return "row=" + id + " bg=" + bg;
            }
            ContentValues cv = new ContentValues();
            cv.put("userId", 0);
            cv.put("pkgName", pkg);
            cv.put("bgControl", "miuiAuto");
            Uri added = cr.insert(uri, cv);
            return added == null ? "insert=null" : "insert=" + added.getLastPathSegment();
        } catch (Throwable t) {
            lastError = "row:" + t.getClass().getSimpleName();
            Log.w(TAG, "ensureRow failed " + pkg, t);
            return "err:" + t.getClass().getSimpleName();
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
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

    /**
     * 「强制禁止」= 智能限制（miui_auto）+ 一切白名单豁免清零。
     * 顺序不能反：先补行（否则 set 直接 -1），再移出 doze，最后写 miui_auto，
     * 这样 get 时的「miuiAuto + 在白名单 -> no_restrict」自动回写不会触发。
     */
    public static String enforce(Context ctx, String pkg, DeviceIdleApi idle) {
        String row = ensureRow(ctx, pkg);
        boolean rm = idle != null && idle.removeForce(pkg);
        boolean w = writeBgControl(ctx, pkg, "miui_auto");
        return "row[" + row + "] rm=" + rm + " write=" + w;
    }

    public static String interfaceValue(int mode) {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                return "no_restrict";
            case Bridge.MODE_DENY:
                return "miui_auto";
            default:
                return "miui_auto";
        }
    }

    public static String internalValue(int mode) {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                return "noRestrict";
            case Bridge.MODE_DENY:
                return "miuiAuto";
            default:
                return "miuiAuto";
        }
    }
}
