package io.github.karvanidx.appman;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Shizuku client using the system-service binder proxy (the classic API).
 *
 * The Shizuku user-service path (bindUserService) is not available on some devices/ROMs,
 * so AppMan calls IPackageManager / IActivityManager through ShizukuBinderWrapper instead.
 * Shizuku executes the transaction with the ADB shell identity, which is why pm/am-level
 * operations work without root.
 *
 * AppMan is built with android:debuggable="true" so hidden API reflection (ServiceManager,
 * transaction codes) is allowed. It is a sideloaded personal app, not a Play Store release.
 */
public final class ShizukuHelper {
    public static final int REQUEST_CODE = 8801;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SVC_PACKAGE = "package";
    private static final String SVC_ACTIVITY = "activity";
    private static final String DESC_PKG = "android.content.pm.IPackageManager";
    private static final String DESC_ACT = "android.app.IActivityManager";
    private static final String STUB_PKG = "android.content.pm.IPackageManager$Stub";
    private static final String STUB_ACT = "android.app.IActivityManager$Stub";

    public interface StateListener {
        void onShizukuStateChanged();
    }

    private interface ParcelWriter {
        void write(Parcel p);
    }

    private static final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Binder DUMMY_OBSERVER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            return true;
        }
    };
    private static String lastError = "";

    private ShizukuHelper() {}

    public static void addStateListener(StateListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeStateListener(StateListener l) {
        listeners.remove(l);
    }

    private static void notifyStateChanged() {
        for (StateListener l : listeners) {
            try {
                l.onShizukuStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku is running and AppMan is authorized: binder calls are usable. */
    public static boolean isReady() {
        return isRunning() && hasPermission();
    }

    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int serverUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            lastError = describe(t);
            notifyStateChanged();
        }
    }

    public static void notifyPermissionChanged() {
        lastError = "";
        notifyStateChanged();
    }

    public static void onBinderDead() {
        lastError = "Shizuku service stopped";
        notifyStateChanged();
    }

    public static String getLastError() {
        return lastError;
    }

    // ------------------------------------------------------------- operations

    public static Result forceStop(String pkg) {
        final int userId = android.os.Process.myUid() / 100000;
        return transact(SVC_ACTIVITY, DESC_ACT, STUB_ACT, "forceStopPackage",
                new ParcelWriter() {
                    @Override
                    public void write(Parcel p) {
                        p.writeString(pkg);
                        p.writeInt(userId);
                    }
                });
    }

    public static Result clearCache(String pkg) {
        final int userId = android.os.Process.myUid() / 100000;
        return transact(SVC_PACKAGE, DESC_PKG, STUB_PKG, "deleteApplicationCacheFilesAsUser",
                new ParcelWriter() {
                    @Override
                    public void write(Parcel p) {
                        p.writeString(pkg);
                        p.writeInt(userId);
                        p.writeStrongBinder(DUMMY_OBSERVER);
                    }
                });
    }

    public static Result clearData(String pkg) {
        final int userId = android.os.Process.myUid() / 100000;
        return transact(SVC_PACKAGE, DESC_PKG, STUB_PKG, "clearApplicationUserData",
                new ParcelWriter() {
                    @Override
                    public void write(Parcel p) {
                        p.writeString(pkg);
                        p.writeStrongBinder(DUMMY_OBSERVER);
                        p.writeInt(userId);
                    }
                });
    }

    private static Result transact(String serviceName, String descriptor, String stubClass,
                                   String methodName, ParcelWriter writer) {
        IBinder target;
        Integer code;
        try {
            target = SystemServiceHelper.getSystemService(serviceName);
            code = SystemServiceHelper.getTransactionCode(stubClass, methodName);
        } catch (Throwable t) {
            lastError = describe(t);
            return new Result(-1, "Cannot access " + serviceName + ": " + describe(t));
        }
        if (target == null) {
            lastError = "System service not found: " + serviceName;
            return new Result(-1, "System service not found: " + serviceName);
        }
        if (code == null) {
            lastError = "Unknown transaction: " + methodName;
            return new Result(-1, "Unknown transaction code for " + methodName);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            writer.write(data);
            IBinder proxy = new ShizukuBinderWrapper(target);
            proxy.transact(code.intValue(), data, reply, 0);
            reply.readException();
            lastError = "";
            return new Result(0, "OK");
        } catch (Throwable t) {
            lastError = describe(t);
            return new Result(-1, describe(t));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static Result selfTest() {
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        try {
            IBinder pkg = SystemServiceHelper.getSystemService(SVC_PACKAGE);
            IBinder act = SystemServiceHelper.getSystemService(SVC_ACTIVITY);
            Integer clearData = SystemServiceHelper.getTransactionCode(STUB_PKG,
                    "clearApplicationUserData");
            Integer clearCache = SystemServiceHelper.getTransactionCode(STUB_PKG,
                    "deleteApplicationCacheFilesAsUser");
            Integer forceStop = SystemServiceHelper.getTransactionCode(STUB_ACT,
                    "forceStopPackage");
            sb.append("Shizuku UID: ").append(serverUid()).append('\n');
            sb.append("package binder: ").append(pkg != null ? "ok" : "missing").append('\n');
            sb.append("activity binder: ").append(act != null ? "ok" : "missing").append('\n');
            sb.append("clearData code: ").append(clearData == null ? "unresolved" : clearData).append('\n');
            sb.append("clearCache code: ").append(clearCache == null ? "unresolved" : clearCache).append('\n');
            sb.append("forceStop code: ").append(forceStop == null ? "unresolved" : forceStop);
            ok = pkg != null && act != null && clearData != null
                    && clearCache != null && forceStop != null;
        } catch (Throwable t) {
            ok = false;
            sb.append("Self-test failed: ").append(describe(t));
        }
        return new Result(ok ? 0 : -1, sb.toString());
    }

    public static String diagnostics(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(c.getPackageName()).append('\n');
        sb.append("Debuggable: ").append(isDebuggable(c)).append('\n');
        sb.append("Shizuku installed: ").append(isShizukuInstalled(c)).append('\n');
        sb.append("Shizuku running: ").append(isRunning()).append('\n');
        if (isRunning()) {
            sb.append("Shizuku version: ").append(version()).append('\n');
            sb.append("Shizuku UID: ").append(serverUid()).append('\n');
            sb.append("Permission: ").append(hasPermission()).append('\n');
            sb.append("Mode: system-service binder proxy\n");
            Result r = selfTest();
            sb.append(r.output);
        }
        if (!lastError.isEmpty()) sb.append("\nLast error: ").append(lastError);
        return sb.toString().trim();
    }

    public static boolean isDebuggable(Context c) {
        try {
            return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShizukuInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openShizuku(Context c) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (i == null) {
                Utils.toast(c, "Shizuku is not installed");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Utils.toast(c, "Cannot open Shizuku");
        }
    }

    private static String describe(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }

        public String message() {
            return output.isEmpty() ? ("exit code " + code) : output;
        }
    }
}
