package com.yg.pkwhitelist;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * hook 侧（PowerKeeper 进程）保存用户的强制策略，以及清除时需要的原始快照。
 *
 * 键位约定：
 *   <pkg>          -> int  策略（0 跟随 / 1 允许 / 2 禁止）
 *   o:<pkg>        -> String  第一次强制前抓到的原始状态，格式 "D1|BmiuiAuto|I1"
 *                              D1/D0 = 原本在/不在 doze 白名单
 *                              Bxxx  = 原本的 bgControl（内部值，可能为空串）
 *                              I1    = 这条 userTable 行是我们插入的，清除时要删掉
 *   p:<pkg>        -> int  被我们从 sAlwaysWhiteApps 里踢出去过，清除时加回
 */
public class OverrideStore {
    private static final String ORIG = "o:";
    private static final String PURGED = "p:";

    private final SharedPreferences sp;

    public OverrideStore(Context ctx) {
        sp = ctx.getSharedPreferences(Bridge.SP, Context.MODE_PRIVATE);
    }

    public int get(String pkg) {
        return sp.getInt(pkg, Bridge.MODE_FOLLOW);
    }

    public void set(String pkg, int mode) {
        sp.edit().putInt(pkg, mode).apply();
    }

    public Map<String, Integer> all() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(ORIG) || k.startsWith(PURGED)) continue;
            Object v = e.getValue();
            if (v instanceof Integer) {
                out.put(k, (Integer) v);
            }
        }
        return out;
    }

    // ---- 原始快照 ----

    public String orig(String pkg) {
        return sp.getString(ORIG + pkg, null);
    }

    /** 只在没有快照时写入，保证记录的是"用户动手之前"的状态 */
    public void saveOrigIfAbsent(String pkg, boolean doze, String bgInternal, boolean insertedByUs) {
        if (sp.contains(ORIG + pkg)) return;
        String v = "D" + (doze ? "1" : "0")
                + "|B" + (bgInternal == null ? "" : bgInternal)
                + "|I" + (insertedByUs ? "1" : "0");
        sp.edit().putString(ORIG + pkg, v).apply();
    }

    public void dropOrig(String pkg) {
        sp.edit().remove(ORIG + pkg).remove(PURGED + pkg).apply();
    }

    // ---- always-white 记录 ----

    public void markPurged(String pkg) {
        sp.edit().putInt(PURGED + pkg, 1).apply();
    }

    public Set<String> purged() {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(PURGED)) {
                out.add(e.getKey().substring(PURGED.length()));
            }
        }
        return out;
    }

    public void dropPurged(String pkg) {
        sp.edit().remove(PURGED + pkg).apply();
    }

    // ---- 一键清除 ----

    public void wipe() {
        sp.edit().clear().apply();
    }
}
