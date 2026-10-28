package io.github.karvanidx.appman;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class BackupManager {
    public interface Listener {
        boolean isCancelled();
        void onProgress(int done, int total, String label);
        void onDone(int ok, int fail, String location);
    }

    private BackupManager() {}

    public static void backup(Context ctx, List<AppEntry> apps, Listener l) {
        int ok = 0;
        int fail = 0;
        String session = "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String root = StorageWriter.ROOT + "/" + session;
        int total = apps.size();
        for (int i = 0; i < total; i++) {
            if (l.isCancelled()) break;
            AppEntry e = apps.get(i);
            l.onProgress(i, total, e.label);
            try {
                backupOne(ctx, root + "/" + Utils.safeName(e.pkg), e);
                ok++;
            } catch (Exception ex) {
                fail++;
            }
            l.onProgress(i + 1, total, e.label);
        }
        l.onDone(ok, fail, root);
    }

    private static void backupOne(Context ctx, String dir, AppEntry e) throws IOException {
        StorageWriter.writeFile(ctx, dir, "base.apk",
                "application/vnd.android.package-archive", new File(e.sourceDir));
        if (e.splitDirs != null) {
            for (int i = 0; i < e.splitDirs.length; i++) {
                if (e.splitDirs[i] == null) continue;
                StorageWriter.writeFile(ctx, dir, "split_" + (i + 1) + ".apk",
                        "application/vnd.android.package-archive", new File(e.splitDirs[i]));
            }
        }
        StorageWriter.writeText(ctx, dir, "info.txt", "text/plain", Utils.appInfoText(e));
    }
}
