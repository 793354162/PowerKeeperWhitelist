package com.yg.pkwhitelist;

/**
 * 模块与 PowerKeeper 进程之间的通信协议。
 * UI 进程（普通 uid）通过 ordered broadcast 发请求，
 * hook 侧在 com.miui.powerkeeper（android.uid.system）进程里处理并回传结果。
 *
 * 安全：接收端用 MODULE_PKG 定义的 signature 权限注册，
 * 只有同签名（也就是本模块）能发出请求；再叠加 getSentFromPackage 校验。
 */
public final class Bridge {
    public static final String PKG = "com.miui.powerkeeper";
    public static final String MODULE_PKG = "com.yg.pkwhitelist";
    public static final String PERM = "com.yg.pkwhitelist.permission.BRIDGE";

    public static final String ACTION_QUERY = "com.yg.pkwhitelist.action.QUERY";
    public static final String ACTION_SET = "com.yg.pkwhitelist.action.SET";
    public static final String ACTION_APPLY_ALL = "com.yg.pkwhitelist.action.APPLY_ALL";
    public static final String ACTION_CLEAR = "com.yg.pkwhitelist.action.CLEAR";

    public static final String EXTRA_PKGS = "pkgs";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RESULT = "result";

    public static final String KEY_PKG = "pkg";
    public static final String KEY_LABEL = "label";
    public static final String KEY_UID = "uid";
    public static final String KEY_SYSTEM = "system";
    public static final String KEY_DOZE = "doze";
    public static final String KEY_BG = "bg";
    public static final String KEY_MODE = "mode";
    public static final String KEY_DIAG = "diag";
    public static final String KEY_REPORT = "report";

    public static final int MODE_FOLLOW = 0;
    public static final int MODE_ALLOW = 1;
    public static final int MODE_DENY = 2;

    public static final String SP = "pk_override";

    private Bridge() {
    }
}
