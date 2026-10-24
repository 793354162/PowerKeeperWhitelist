package com.yg.pkwhitelist;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 反射调用系统 IDeviceIdleController（隐藏 API）。
 * 只在 hook 侧（PowerKeeper 进程，android.uid.system）使用。
 */
public class DeviceIdleApi {
    private static final String TAG = "PKWL";
    public static volatile String lastError = "";
    private Object controller;

    public DeviceIdleApi() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "deviceidle");
            if (binder == null) {
                lastError = "service=null";
                return;
            }
            Class<?> stub = Class.forName("android.os.IDeviceIdleController$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            controller = asInterface.invoke(null, binder);
        } catch (Throwable t) {
            lastError = "init:" + t.getClass().getSimpleName();
            Log.w(TAG, "init DeviceIdleApi failed", t);
        }
    }

    public boolean available() {
        return controller != null;
    }

    public boolean isWhitelisted(String pkg, int userId) {
        if (controller == null) return false;
        Object r = invoke("isPowerSaveWhitelistApp", new Class[]{String.class, int.class}, new Object[]{pkg, userId});
        if (r == null) {
            r = invoke("isPowerSaveWhitelistApp", new Class[]{String.class}, new Object[]{pkg});
        }
        if (r == null) {
            lastError = "isWhitelisted=null";
            return false;
        }
        return (Boolean) r;
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
            lastError = name + ":" + t.getClass().getSimpleName();
            return null;
        }
    }
}
