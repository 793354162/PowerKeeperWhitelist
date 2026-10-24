package com.yg.pkwhitelist;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 反射调用系统 IDeviceIdleController（隐藏 API）。
 * 只在 hook 侧（PowerKeeper 进程，android.uid.system）使用，普通 app 进程没有权限。
 */
public class DeviceIdleApi {
    private static final String TAG = "PKWL";
    private Object controller;

    public DeviceIdleApi() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "deviceidle");
            if (binder == null) {
                Log.w(TAG, "deviceidle service not found");
                return;
            }
            Class<?> stub = Class.forName("android.os.IDeviceIdleController$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            controller = asInterface.invoke(null, binder);
        } catch (Throwable t) {
            Log.w(TAG, "init DeviceIdleApi failed", t);
        }
    }

    public boolean available() {
        return controller != null;
    }

    public boolean isWhitelisted(String pkg, int userId) {
        if (controller == null) return false;
        // Android 13+ 可能带 userId 参数，旧版只有包名
        Object r = invoke("isPowerSaveWhitelistApp", new Class[]{String.class, int.class}, new Object[]{pkg, userId});
        if (r == null) {
            r = invoke("isPowerSaveWhitelistApp", new Class[]{String.class}, new Object[]{pkg});
        }
        return r instanceof Boolean && (Boolean) r;
    }

    public void add(String pkg) {
        if (controller == null) return;
        invoke("addPowerSaveWhitelistApp", new Class[]{String.class}, new Object[]{pkg});
    }

    public void remove(String pkg) {
        if (controller == null) return;
        invoke("removePowerSaveWhitelistApp", new Class[]{String.class}, new Object[]{pkg});
    }

    private Object invoke(String name, Class<?>[] types, Object[] args) {
        try {
            Method m = controller.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(controller, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
