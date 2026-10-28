package io.github.karvanidx.appman;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * Thin wrapper around Shizuku (no-root shell service started over Wireless debugging).
 * Commands run in our own user-service process, which Shizuku starts with the shell UID.
 */
public final class ShizukuHelper {
    public static final int REQUEST_CODE = 8801;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    public interface StateListener {
        void onShizukuStateChanged();
    }

    private static final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    private static IBinder shellBinder;
    private static boolean binding;
    private static String lastError = "";
    private static long bindingStartedAt;
    private static Shizuku.UserServiceArgs serviceArgs;
    private static ServiceConnection connection;
    private static Runnable onServiceReady;

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

    public static void setOnServiceReady(Runnable r) {
        onServiceReady = r;
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

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            lastError = "requestPermission: " + describe(t);
            notifyStateChanged();
        }
    }

    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean isServiceReady() {
        IBinder b = shellBinder;
        try {
            return b != null && b.isBinderAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isBinding() {
        return binding;
    }

    public static String getLastError() {
        return lastError;
    }

    public static long getBindingStartedAt() {
        return bindingStartedAt;
    }

    public static void onBindTimeout() {
        if (!isServiceReady()) {
            binding = false;
            lastError = "Timed out waiting for the shell service";
            notifyStateChanged();
        }
    }

    public static void onBinderDead() {
        shellBinder = null;
        binding = false;
        lastError = "Shizuku service stopped";
        notifyStateChanged();
    }

    public static void bindService(Context ctx) {
        if (binding || isServiceReady() || !isRunning() || !hasPermission()) return;
        binding = true;
        bindingStartedAt = System.currentTimeMillis();
        lastError = "";
        if (serviceArgs == null) {
            serviceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(ctx.getPackageName(), ShellService.class.getName()))
                    .processNameSuffix("shell")
                    .version(1)
                    .debuggable(false)
                    .daemon(false);
        }
        if (connection == null) {
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    shellBinder = service;
                    binding = false;
                    lastError = "";
                    notifyStateChanged();
                    Runnable r = onServiceReady;
                    if (r != null) r.run();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    shellBinder = null;
                    binding = false;
                    lastError = "Shell service disconnected";
                    notifyStateChanged();
                }
            };
        }
        notifyStateChanged();
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable t) {
            binding = false;
            lastError = describe(t);
            notifyStateChanged();
        }
    }

    /** Fully remove the user service and allow a clean restart. */
    public static void resetService() {
        try {
            if (serviceArgs != null && connection != null) {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            }
        } catch (Throwable ignored) {
        }
        shellBinder = null;
        binding = false;
        lastError = "";
        serviceArgs = null;
        connection = null;
        notifyStateChanged();
    }

    public static Result run(String command) {
        IBinder b = shellBinder;
        if (b == null || !b.isBinderAlive()) {
            return new Result(-1, "Shizuku shell service is not connected");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR);
            data.writeString(command);
            if (!b.transact(ShellService.TRANSACTION_RUN, data, reply, 0)) {
                return new Result(-1, "Shell transaction was rejected");
            }
            reply.readException();
            int code = reply.readInt();
            String out = reply.readString();
            return new Result(code, out);
        } catch (Throwable t) {
            return new Result(-1, describe(t));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static String diagnostics(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(c.getPackageName()).append('\n');
        sb.append("Shizuku installed: ").append(isShizukuInstalled(c)).append('\n');
        sb.append("Shizuku running: ").append(isRunning()).append('\n');
        if (isRunning()) {
            sb.append("Shizuku version: ").append(version()).append('\n');
            sb.append("Permission: ").append(hasPermission()).append('\n');
            sb.append("Shell service: ").append(isServiceReady() ? "connected" : (binding ? "connecting" : "not started")).append('\n');
            if (isServiceReady()) {
                Result r = run("id");
                sb.append("id: ").append(r.output).append('\n');
            }
        }
        if (!lastError.isEmpty()) sb.append("Last error: ").append(lastError).append('\n');
        return sb.toString().trim();
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
