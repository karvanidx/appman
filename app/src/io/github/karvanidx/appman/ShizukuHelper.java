package io.github.karvanidx.appman;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    // Transaction codes extracted from this device's Android 16 framework.jar.
    // Used as a fallback when Shizuku's transaction-code reflection is unavailable.
    private static final int CODE_CLEAR_DATA = 81;   // IActivityManager.clearApplicationUserData
    private static final int CODE_CLEAR_CACHE = 98;  // IPackageManager.deleteApplicationCacheFilesAsUser
    private static final int CODE_FORCE_STOP = 85;   // IActivityManager.forceStopPackage
    private static final int CODE_DELETE = 51;       // IPackageManager.deletePackageAsUser
    private static final int CODE_RUNNING = 89;      // IActivityManager.getRunningAppProcesses

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

    public static Result uninstall(String pkg) {
        IBinder target;
        try {
            target = SystemServiceHelper.getSystemService(SVC_PACKAGE);
        } catch (Throwable t) {
            return new Result(-1, "Cannot access package service: " + describe(t));
        }
        if (target == null) return new Result(-1, "Package service not found");
        int code = resolveCode(STUB_PKG, "deletePackageAsUser", CODE_DELETE);
        DeleteObserver observer = new DeleteObserver(pkg);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_PKG);
            data.writeString(pkg);
            data.writeInt(-1); // VERSION_CODE_HIGHEST
            data.writeStrongBinder(observer);
            data.writeInt(android.os.Process.myUid() / 100000); // userId
            data.writeInt(0); // flags
            IBinder proxy = new ShizukuBinderWrapper(target);
            proxy.transact(code, data, reply, 0);
            reply.readException();
            if (!observer.latch.await(60, TimeUnit.SECONDS)) {
                return new Result(-1, "Timed out waiting for uninstall to finish");
            }
            if (observer.returnCode != 1) {
                return new Result(-1, "Uninstall failed (code " + observer.returnCode + ")");
            }
            return new Result(0, "Uninstalled");
        } catch (Throwable t) {
            return new Result(-1, describe(t));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static final class DeleteObserver extends Binder {
        final CountDownLatch latch = new CountDownLatch(1);
        final String expected;
        volatile int returnCode = Integer.MIN_VALUE;

        DeleteObserver(String expected) {
            this.expected = expected;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 1) {
                data.enforceInterface("android.content.pm.IPackageDeleteObserver");
                data.readString();
                returnCode = data.readInt();
                latch.countDown();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /** All running process packages, including background processes (needs Shizuku). */
    public static Set<String> runningPackages() {
        Set<String> out = new HashSet<>();
        if (!isReady()) return out;
        IBinder target;
        try {
            target = SystemServiceHelper.getSystemService(SVC_ACTIVITY);
        } catch (Throwable t) {
            return out;
        }
        if (target == null) return out;
        int code = resolveCode(STUB_ACT, "getRunningAppProcesses", CODE_RUNNING);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_ACT);
            IBinder proxy = new ShizukuBinderWrapper(target);
            proxy.transact(code, data, reply, 0);
            reply.readException();
            ArrayList<ActivityManager.RunningAppProcessInfo> list =
                    reply.createTypedArrayList(ActivityManager.RunningAppProcessInfo.CREATOR);
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo info : list) {
                    if (info.processName == null) continue;
                    out.add(info.processName.split(":")[0]);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return out;
    }

    public static Result forceStop(String pkg) {
        final int userId = android.os.Process.myUid() / 100000;
        return transact(SVC_ACTIVITY, DESC_ACT, STUB_ACT, "forceStopPackage",
                CODE_FORCE_STOP, false, null, new ParcelWriter() {
                    @Override
                    public void write(Parcel p) {
                        p.writeString(pkg);
                        p.writeInt(userId);
                    }
                });
    }

    public static Result clearCache(String pkg) {
        final int userId = android.os.Process.myUid() / 100000;
        // Some Android 16 ROMs clear the cache but never deliver onRemoveCompleted,
        // so do not wait for the observer here (it would always time out).
        return transact(SVC_PACKAGE, DESC_PKG, STUB_PKG, "deleteApplicationCacheFilesAsUser",
                CODE_CLEAR_CACHE, false, null, new ParcelWriter() {
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
        DataObserver observer = new DataObserver();
        return transact(SVC_ACTIVITY, DESC_ACT, STUB_ACT, "clearApplicationUserData",
                CODE_CLEAR_DATA, true, observer, new ParcelWriter() {
                    @Override
                    public void write(Parcel p) {
                        p.writeString(pkg);
                        p.writeBoolean(false); // keepState
                        p.writeStrongBinder(observer);
                        p.writeInt(userId);
                    }
                });
    }

    private static int resolveCode(String stubClass, String methodName, int fallback) {
        try {
            Integer code = SystemServiceHelper.getTransactionCode(stubClass, methodName);
            if (code != null) return code.intValue();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static Result transact(String serviceName, String descriptor, String stubClass,
                                   String methodName, int fallbackCode, boolean readAccepted,
                                   DataObserver observer, ParcelWriter writer) {
        IBinder target;
        try {
            target = SystemServiceHelper.getSystemService(serviceName);
        } catch (Throwable t) {
            lastError = describe(t);
            return new Result(-1, "Cannot access " + serviceName + ": " + describe(t));
        }
        if (target == null) {
            lastError = "System service not found: " + serviceName;
            return new Result(-1, "System service not found: " + serviceName);
        }
        int code = resolveCode(stubClass, methodName, fallbackCode);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            writer.write(data);
            IBinder proxy = new ShizukuBinderWrapper(target);
            proxy.transact(code, data, reply, 0);
            reply.readException();
            if (readAccepted) {
                boolean accepted = reply.readInt() != 0;
                if (!accepted) {
                    lastError = "System rejected " + methodName;
                    return new Result(-1, "System rejected " + methodName);
                }
            }
            if (observer != null) {
                if (!observer.latch.await(15, TimeUnit.SECONDS)) {
                    lastError = "Timed out waiting for " + methodName;
                    return new Result(-1, "Timed out waiting for the system to finish");
                }
                if (!observer.success) {
                    lastError = methodName + " reported failure";
                    return new Result(-1, "The system reported failure");
                }
            }
            lastError = "";
            return new Result(0, "OK (transaction " + code + ")");
        } catch (Throwable t) {
            lastError = describe(t);
            return new Result(-1, describe(t));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static final class DataObserver extends Binder {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 1) {
                data.enforceInterface("android.content.pm.IPackageDataObserver");
                data.readString();
                success = data.readInt() != 0;
                latch.countDown();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    public static Result selfTest() {
        StringBuilder sb = new StringBuilder();
        boolean ok = false;
        try {
            IBinder pkg = SystemServiceHelper.getSystemService(SVC_PACKAGE);
            IBinder act = SystemServiceHelper.getSystemService(SVC_ACTIVITY);
            int clearData = resolveCode(STUB_ACT, "clearApplicationUserData", CODE_CLEAR_DATA);
            int clearCache = resolveCode(STUB_PKG, "deleteApplicationCacheFilesAsUser", CODE_CLEAR_CACHE);
            int forceStop = resolveCode(STUB_ACT, "forceStopPackage", CODE_FORCE_STOP);
            sb.append("Shizuku UID: ").append(serverUid()).append('\n');
            sb.append("package binder: ").append(pkg != null ? "ok" : "missing").append('\n');
            sb.append("activity binder: ").append(act != null ? "ok" : "missing").append('\n');
            sb.append("clearData code: ").append(clearData).append('\n');
            sb.append("clearCache code: ").append(clearCache).append('\n');
            sb.append("forceStop code: ").append(forceStop);
            ok = pkg != null && act != null;
        } catch (Throwable t) {
            sb.append("Self-test failed: ").append(describe(t));
        }
        return new Result(ok ? 0 : -1, sb.toString());
    }

    public static String diagnostics(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(c.getPackageName()).append(" ")
                .append(appVersion(c)).append('\n');
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

    private static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
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
