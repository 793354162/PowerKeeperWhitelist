package com.yg.pkwhitelist;

public class AppEntry {
    public String pkg;
    public String label;
    public String bg = "";
    public int uid;
    public int mode = Bridge.MODE_FOLLOW;
    public boolean system;
    public boolean doze;
    public boolean launcher;

    public AppEntry(String pkg, String label, boolean system) {
        this.pkg = pkg;
        this.label = label;
        this.system = system;
    }

    public String bgText() {
        if (bg == null) return "未知";
        switch (bg) {
            case "noRestrict":
                return "无限制";
            case "miuiAuto":
                return "智能";
            case "restrictBg":
                return "受限";
            case "noBg":
                return "禁止后台";
            default:
                return bg.isEmpty() ? "未知" : bg;
        }
    }

    public String modeText() {
        switch (mode) {
            case Bridge.MODE_ALLOW:
                return "强制允许";
            case Bridge.MODE_DENY:
                return "强制禁止";
            default:
                return "跟随系统";
        }
    }
}
