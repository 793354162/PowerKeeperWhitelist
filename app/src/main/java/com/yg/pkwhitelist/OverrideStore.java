package com.yg.pkwhitelist;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** hook 侧（PowerKeeper 进程）保存用户的强制策略。 */
public class OverrideStore {
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
            Object v = e.getValue();
            if (v instanceof Integer) {
                out.put(e.getKey(), (Integer) v);
            }
        }
        return out;
    }
}
