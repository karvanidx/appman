package io.github.karvanidx.appman;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Runs inside a Shizuku user-service process, which has the identity of the ADB shell user
 * (UID 2000). This is why it can execute "pm clear" and "am force-stop" without root.
 */
public class ShellService extends Binder {
    public static final String DESCRIPTOR = "io.github.karvanidx.appman.IShellService";
    public static final int TRANSACTION_RUN = 1;
    public static final int TRANSACTION_DESTROY = 16777115;

    public ShellService() {
    }

    @SuppressWarnings("unused")
    public ShellService(Context context) {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == TRANSACTION_DESTROY) {
            System.exit(0);
            return true;
        }
        if (code != TRANSACTION_RUN) {
            return super.onTransact(code, data, reply, flags);
        }

        data.enforceInterface(DESCRIPTOR);
        String command = data.readString();
        int exit = -1;
        String output = "";
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/sh", "-c", command + " 2>&1"});
            InputStream in = p.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            exit = p.waitFor();
            output = bos.toString("UTF-8").trim();
        } catch (Throwable t) {
            output = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        reply.writeNoException();
        reply.writeInt(exit);
        reply.writeString(output);
        return true;
    }
}
