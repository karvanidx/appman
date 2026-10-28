package io.github.karvanidx.appman;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_UNINSTALL = 101;
    private static final int REQ_WRITE = 102;

    private PackageManager pm;
    private ExecutorService io;
    private Handler ui;
    private AppAdapter adapter;

    private List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Map<String, AppEntry> byPkg = new HashMap<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private boolean selectionMode = false;
    private int filterMode = 0;
    private int sortMode = 0;
    private int loadToken = 0;
    private boolean recentSortWarned = false;

    private EditText etSearch;
    private Spinner spFilter;
    private Spinner spSort;
    private ListView listView;
    private View emptyView;
    private View bottomBar;
    private View statusBar;
    private View dotShizuku;
    private TextView tvSummary;
    private TextView tvStatus;
    private TextView tvSelected;
    private TextView tvShizukuChip;
    private Button btnShareSel;
    private ProgressBar progressBar;

    private AlertDialog progressDialog;
    private TextView pTitle;
    private TextView pMsg;
    private ProgressBar pBar;

    private List<AppEntry> pendingBackup;
    private volatile boolean cancelBackup;
    private final ArrayDeque<String> uninstallQueue = new ArrayDeque<>();

    private Runnable pendingShizukuAction;
    private Operation activeOperation;
    private Shizuku.OnRequestPermissionResultListener shizukuPermListener;
    private Shizuku.OnBinderReceivedListener shizukuBinderListener;
    private Shizuku.OnBinderDeadListener shizukuDeadListener;
    private ShizukuHelper.StateListener shizukuStateListener;

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            loadApps();
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ui.removeCallbacks(reloadRunnable);
            ui.postDelayed(reloadRunnable, 800);
        }
    };
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        io = Executors.newSingleThreadExecutor();
        ui = new Handler(Looper.getMainLooper());
        setContentView(R.layout.activity_main);

        etSearch = findViewById(R.id.et_search);
        spFilter = findViewById(R.id.sp_filter);
        spSort = findViewById(R.id.sp_sort);
        listView = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        bottomBar = findViewById(R.id.bottom_bar);
        statusBar = findViewById(R.id.status_bar);
        dotShizuku = findViewById(R.id.dot_shizuku);
        tvSummary = findViewById(R.id.tv_summary);
        tvStatus = findViewById(R.id.tv_status);
        tvSelected = findViewById(R.id.tv_selected);
        tvShizukuChip = findViewById(R.id.tv_shizuku_chip);
        btnShareSel = findViewById(R.id.btn_share_sel);
        progressBar = findViewById(R.id.progress);

        adapter = new AppAdapter(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        setupSpinners();
        setupListeners();
        initShizuku();
        updateShizukuIndicator();
        loadApps();
    }

    private void setupSpinners() {
        ArrayAdapter<String> fa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"All apps", "User apps", "System apps"});
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(fa);
        spFilter.setSelection(filterMode);
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filterMode = position;
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Sort: Name", "Sort: Size", "Sort: Updated", "Sort: Package", "Sort: Recent"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSort.setAdapter(sa);
        spSort.setSelection(sortMode);
        spSort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sortMode = position;
                if (sortMode == 4 && !Utils.usageAccessGranted(MainActivity.this)
                        && !recentSortWarned) {
                    recentSortWarned = true;
                    Utils.toast(MainActivity.this,
                            "Recent sort needs usage access (menu → Usage access settings)");
                }
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadApps();
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etSearch.setText("");
            }
        });
        findViewById(R.id.shizuku_chip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateShizukuIndicator();
                showShizukuStatus();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppEntry e = shown.get(position);
                if (selectionMode) toggleSelection(e.pkg);
                else showDetails(e);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                AppEntry e = shown.get(position);
                if (!selectionMode) enterSelection(e.pkg);
                else toggleSelection(e.pkg);
                return true;
            }
        });

        findViewById(R.id.btn_backup_sel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<AppEntry> apps = selectedEntries();
                if (!apps.isEmpty()) confirmBackup(apps);
            }
        });
        findViewById(R.id.btn_share_sel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<AppEntry> apps = selectedEntries();
                if (apps.size() == 1) shareApp(apps.get(0));
                else Utils.toast(MainActivity.this, "Select exactly one app to share");
            }
        });
        findViewById(R.id.btn_uninstall_sel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String> pkgs = new ArrayList<>(selected);
                if (!pkgs.isEmpty()) confirmUninstall(pkgs);
            }
        });
        findViewById(R.id.btn_cancel_sel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelection();
            }
        });
    }

    // ---------------------------------------------------------------- loading

    private void loadApps() {
        final int token = ++loadToken;
        setBusy(true, "Scanning installed apps…");
        tvSummary.setText("Scanning…");
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> result = AppRepo.load(appCtx, new AppRepo.Progress() {
                    @Override
                    public void onProgress(final int count) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (token == loadToken) {
                                    tvStatus.setText("Scanning… " + count + " apps");
                                }
                            }
                        });
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken || isFinishing()) return;
                        allApps = result;
                        byPkg.clear();
                        for (AppEntry e : allApps) byPkg.put(e.pkg, e);
                        if (selectionMode) {
                            selected.retainAll(byPkg.keySet());
                            if (selected.isEmpty()) exitSelection();
                        }
                        setBusy(false, null);
                        applyFilter();
                    }
                });
            }
        });
    }

    private void applyFilter() {
        if (etSearch == null) return;
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        long total = 0;
        for (AppEntry e : allApps) {
            if (filterMode == 1 && e.system) continue;
            if (filterMode == 2 && !e.system) continue;
            if (!q.isEmpty() && !e.searchText.contains(q)) continue;
            shown.add(e);
            total += e.apkSize;
        }
        Collections.sort(shown, comparator());
        adapter.setItems(shown);
        adapter.setSelectionState(selectionMode, selected);
        tvSummary.setText(shown.size() + " of " + allApps.size() + " apps  •  "
                + Utils.humanSize(total));
        if (selectionMode) updateSelectionUi();
    }

    private Comparator<AppEntry> comparator() {
        return new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                switch (sortMode) {
                    case 1:
                        return Long.compare(b.apkSize, a.apkSize);
                    case 2:
                        return Long.compare(b.updated, a.updated);
                    case 3:
                        return a.pkg.compareToIgnoreCase(b.pkg);
                    case 4:
                        int c = Long.compare(b.lastUsed, a.lastUsed);
                        return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
                    default:
                        return a.label.compareToIgnoreCase(b.label);
                }
            }
        };
    }

    // -------------------------------------------------------------- selection

    private void enterSelection(String pkg) {
        selectionMode = true;
        selected.clear();
        selected.add(pkg);
        adapter.setSelectionState(true, selected);
        updateSelectionUi();
    }

    private void toggleSelection(String pkg) {
        if (selected.contains(pkg)) selected.remove(pkg);
        else selected.add(pkg);
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        adapter.setSelectionState(true, selected);
        updateSelectionUi();
    }

    private void exitSelection() {
        selectionMode = false;
        selected.clear();
        adapter.setSelectionState(false, selected);
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        bottomBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (!selectionMode) {
            tvSelected.setText("");
            return;
        }
        long size = 0;
        for (String p : selected) {
            AppEntry e = byPkg.get(p);
            if (e != null) size += e.apkSize;
        }
        tvSelected.setText(selected.size() + " selected"
                + (size > 0 ? "  •  " + Utils.humanSize(size) : ""));
        boolean one = selected.size() == 1;
        btnShareSel.setEnabled(one);
        btnShareSel.setAlpha(one ? 1f : 0.4f);
    }

    private List<AppEntry> selectedEntries() {
        List<AppEntry> out = new ArrayList<>();
        for (String p : selected) {
            AppEntry e = byPkg.get(p);
            if (e != null) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add(0, 1, 0, "Refresh");
        p.getMenu().add(0, 2, 1, "Backup all user apps");
        p.getMenu().add(0, 3, 2, "Export app list (CSV)");
        p.getMenu().add(0, 4, 3, "Usage analytics");
        p.getMenu().add(0, 5, 4, "Usage access settings");
        p.getMenu().add(0, 6, 5, "Shizuku status");
        p.getMenu().add(0, 7, 6, "About AppMan");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        loadApps();
                        return true;
                    case 2:
                        backupAllUser();
                        return true;
                    case 3:
                        exportCsv();
                        return true;
                    case 4:
                        startActivity(new Intent(MainActivity.this, AnalyticsActivity.class));
                        return true;
                    case 5:
                        Utils.openUsageAccessSettings(MainActivity.this);
                        return true;
                    case 6:
                        showShizukuStatus();
                        return true;
                    case 7:
                        showAbout();
                        return true;
                }
                return false;
            }
        });
        p.show();
    }

    private void backupAllUser() {
        List<AppEntry> user = new ArrayList<>();
        for (AppEntry e : allApps) if (!e.system) user.add(e);
        if (user.isEmpty()) {
            Utils.toast(this, "No user apps found yet");
            return;
        }
        confirmBackup(user);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("AppMan 1.3.1")
                .setMessage("A lightweight no-root app manager and usage tracker, built in Termux.\n\n"
                        + "• Lists all installed apps (user + system)\n"
                        + "• Backup APKs (including split APKs) to Download/AppMan\n"
                        + "• Share APK files\n"
                        + "• Uninstall via the Android confirmation dialog\n"
                        + "• Export the app list as CSV\n"
                        + "• Screen time, launches and last-used analytics\n"
                        + "• Force stop / clear cache / clear data via Shizuku (binder mode)\n\n"
                        + "Clear cache & data of other apps needs Shizuku (shell access over "
                        + "Wireless debugging, no root).\n\n"
                        + "Built with Pi agent + DeepSeek.\n"
                        + "github.com/karvanidx")
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------------------------------------------------------------- backup

    private void confirmBackup(final List<AppEntry> apps) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingBackup = apps;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        long total = 0;
        for (AppEntry e : apps) total += e.apkSize;
        new AlertDialog.Builder(this)
                .setTitle(apps.size() == 1 ? "Backup APK" : "Backup " + apps.size() + " APKs")
                .setMessage("Save to Download/AppMan/?\n\nTotal APK size: " + Utils.humanSize(total)
                        + "\n\nOnly APK files are saved. App data is not included (would require root).")
                .setPositiveButton("Backup", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startBackup(apps);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startBackup(final List<AppEntry> apps) {
        final List<AppEntry> copy = new ArrayList<>(apps);
        cancelBackup = false;
        showProgressDialog("Backing up APKs", copy.size());
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                BackupManager.backup(appCtx, copy, new BackupManager.Listener() {
                    @Override
                    public boolean isCancelled() {
                        return cancelBackup;
                    }

                    @Override
                    public void onProgress(final int done, final int total, final String label) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (pBar != null) {
                                    pBar.setMax(Math.max(total, 1));
                                    pBar.setProgress(done);
                                }
                                if (pMsg != null) pMsg.setText(done + " / " + total + "   " + label);
                            }
                        });
                    }

                    @Override
                    public void onDone(final int ok, final int fail, final String location) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                hideProgressDialog();
                                boolean cancelled = cancelBackup;
                                cancelBackup = false;
                                if (selectionMode) exitSelection();
                                String msg = (cancelled ? "Cancelled. " : "")
                                        + ok + " backed up"
                                        + (fail > 0 ? ", " + fail + " failed" : "")
                                        + "\n\n" + location;
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("Backup finished")
                                        .setMessage(msg)
                                        .setPositiveButton("OK", null)
                                        .show();
                            }
                        });
                    }
                });
            }
        });
    }

    // ----------------------------------------------------------------- share

    private void shareApp(final AppEntry e) {
        setBusy(true, "Preparing APK…");
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = ApkProvider.sharedDir(appCtx);
                    Uri uri;
                    String mime;
                    if (e.splitDirs != null && e.splitDirs.length > 0) {
                        File zip = new File(dir, Utils.safeName(e.pkg) + "-" + e.versionName + ".zip");
                        Utils.zipApks(zip, e);
                        uri = ApkProvider.uriFor(zip);
                        mime = "application/zip";
                    } else {
                        File out = new File(dir, Utils.safeName(e.pkg) + "-" + e.versionName + ".apk");
                        Utils.copyFile(new File(e.sourceDir), out);
                        uri = ApkProvider.uriFor(out);
                        mime = "application/vnd.android.package-archive";
                    }
                    final Uri furi = uri;
                    final String fmime = mime;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType(fmime);
                            send.putExtra(Intent.EXTRA_STREAM, furi);
                            send.setClipData(ClipData.newRawUri("APK", furi));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try {
                                startActivity(Intent.createChooser(send, "Share " + e.label));
                            } catch (Exception ex) {
                                Utils.toast(MainActivity.this, "No app to share with");
                            }
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            Utils.toast(MainActivity.this, "Share failed: " + ex.getMessage());
                        }
                    });
                }
            }
        });
    }

    // -------------------------------------------------------------- uninstall

    private void confirmUninstall(List<String> pkgs) {
        if (pkgs.isEmpty()) return;
        String title;
        String message;
        if (pkgs.size() == 1) {
            AppEntry e = byPkg.get(pkgs.get(0));
            title = "Uninstall";
            message = "Uninstall " + (e != null ? e.label : pkgs.get(0)) + "?\n\n"
                    + "Android will ask you to confirm. System apps may only be disabled.";
        } else {
            title = "Uninstall " + pkgs.size() + " apps";
            message = "You will be asked to confirm each app.\n\n"
                    + "System apps may only be disabled.";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestUninstall(pkgs);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestUninstall(List<String> pkgs) {
        uninstallQueue.clear();
        uninstallQueue.addAll(pkgs);
        if (selectionMode) exitSelection();
        startNextUninstall();
    }

    private void startNextUninstall() {
        final String pkg = uninstallQueue.poll();
        if (pkg == null) {
            loadApps();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(i, REQ_UNINSTALL);
        } catch (Exception ex) {
            Utils.toast(this, "Cannot uninstall " + pkg + ": " + ex.getMessage());
            startNextUninstall();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL) {
            if (uninstallQueue.isEmpty()) {
                loadApps();
            } else {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startNextUninstall();
                    }
                }, 400);
            }
        }
    }

    // ---------------------------------------------------------------- details

    private void showDetails(final AppEntry e) {
        View v = getLayoutInflater().inflate(R.layout.dialog_details, null);
        ImageView icon = v.findViewById(R.id.d_icon);
        TextView name = v.findViewById(R.id.d_name);
        TextView pkg = v.findViewById(R.id.d_pkg);
        TextView info = v.findViewById(R.id.d_info);
        TextView sizes = v.findViewById(R.id.d_sizes);
        TextView shizukuStatus = v.findViewById(R.id.d_shizuku_status);
        Button usage = v.findViewById(R.id.d_usage_access);
        Button forceStop = v.findViewById(R.id.d_force_stop);
        Button clearCache = v.findViewById(R.id.d_clear_cache);
        Button clearData = v.findViewById(R.id.d_clear_data);

        icon.setImageDrawable(e.icon != null ? e.icon
                : getDrawable(android.R.drawable.sym_def_app_icon));
        name.setText(e.label);
        pkg.setText(e.pkg);

        StringBuilder sb = new StringBuilder();
        sb.append("Version: ").append(e.versionName).append(" (").append(e.versionCode).append(")\n");
        sb.append("SDK: min ").append(e.minSdk).append("  •  target ").append(e.targetSdk).append('\n');
        sb.append("Type: ").append(e.system ? "System" : "User").append('\n');
        sb.append("Updated: ").append(Utils.fmtDate(e.updated)).append('\n');
        sb.append("Installer: ").append(e.installer == null ? "—" : e.installer).append('\n');
        sb.append("Last used: ").append(e.lastUsed > 0 ? Utils.ago(e.lastUsed) : "—").append('\n');
        if (Utils.usageAccessGranted(this)) {
            sb.append("Screen time (30d): ").append(Utils.humanDuration(e.totalForeground)).append('\n');
        }
        sb.append("APK size: ").append(Utils.humanSize(e.apkSize));
        if (e.splitDirs != null && e.splitDirs.length > 0) {
            sb.append("  (").append(1 + e.splitDirs.length).append(" APK parts)");
        }
        sb.append('\n');
        sb.append("APK: ").append(e.sourceDir);
        info.setText(sb.toString());

        loadSizesInto(sizes, usage, e);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(v)
                .setNegativeButton("Close", null)
                .create();

        final Runnable refreshPower = new Runnable() {
            @Override
            public void run() {
                boolean ready = ShizukuHelper.isReady();
                forceStop.setEnabled(ready);
                clearCache.setEnabled(ready);
                clearData.setEnabled(ready);
                float alpha = ready ? 1f : 0.42f;
                forceStop.setAlpha(alpha);
                clearCache.setAlpha(alpha);
                clearData.setAlpha(alpha);
                if (!ShizukuHelper.isRunning()) {
                    shizukuStatus.setText("Shizuku is off — tap to set up (no root needed)");
                    shizukuStatus.setTextColor(0xFFC62828);
                } else if (!ShizukuHelper.hasPermission()) {
                    shizukuStatus.setText("Shizuku is running — tap to grant permission");
                    shizukuStatus.setTextColor(0xFFF57C00);
                } else {
                    shizukuStatus.setText("Shizuku ready — force stop & cache clearing enabled");
                    shizukuStatus.setTextColor(0xFF2E7D32);
                }
            }
        };
        refreshPower.run();
        shizukuStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showShizukuStatus();
            }
        });
        final ShizukuHelper.StateListener dialogListener = new ShizukuHelper.StateListener() {
            @Override
            public void onShizukuStateChanged() {
                ui.post(refreshPower);
            }
        };
        ShizukuHelper.addStateListener(dialogListener);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                ShizukuHelper.removeStateListener(dialogListener);
            }
        });

        final Runnable refreshSizes = new Runnable() {
            @Override
            public void run() {
                if (dlg.isShowing()) loadSizesInto(sizes, usage, e);
            }
        };

        usage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Utils.openUsageAccessSettings(MainActivity.this);
            }
        });
        v.findViewById(R.id.d_open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                Utils.startApp(MainActivity.this, e.pkg);
            }
        });
        v.findViewById(R.id.d_backup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                confirmBackup(Collections.singletonList(e));
            }
        });
        v.findViewById(R.id.d_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                shareApp(e);
            }
        });
        v.findViewById(R.id.d_appinfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Utils.openAppInfo(MainActivity.this, e.pkg);
            }
        });
        v.findViewById(R.id.d_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Utils.copyText(MainActivity.this, "Package", e.pkg);
            }
        });
        v.findViewById(R.id.d_uninstall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                confirmUninstall(Collections.singletonList(e.pkg));
            }
        });

        forceStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                powerAction(e, "Force stop · " + e.label,
                        "forceStopPackage(" + e.pkg + ")",
                        "Force-stopped " + e.label, null, new PowerCall() {
                            @Override
                            public ShizukuHelper.Result run() {
                                return ShizukuHelper.forceStop(e.pkg);
                            }
                        });
            }
        });
        clearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                powerAction(e, "Clear cache · " + e.label,
                        "deleteApplicationCacheFilesAsUser(" + e.pkg + ")",
                        "Cache cleared for " + e.label, refreshSizes, new PowerCall() {
                            @Override
                            public ShizukuHelper.Result run() {
                                return ShizukuHelper.clearCache(e.pkg);
                            }
                        });
            }
        });
        clearData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Clear data?")
                        .setMessage("Erase ALL data of \"" + e.label + "\"?\n\n"
                                + "This is permanent and the app will be force-stopped.")
                        .setPositiveButton("Clear data", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                powerAction(e, "Clear data · " + e.label,
                                        "clearApplicationUserData(" + e.pkg + ")",
                                        "Data cleared for " + e.label, refreshSizes, new PowerCall() {
                                            @Override
                                            public ShizukuHelper.Result run() {
                                                return ShizukuHelper.clearData(e.pkg);
                                            }
                                        });
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        dlg.show();
    }

    private void loadSizesInto(final TextView target, final View usageBtn, final AppEntry e) {
        if (!Utils.usageAccessGranted(this)) {
            usageBtn.setVisibility(View.VISIBLE);
            target.setText("Data & cache: enable usage access above");
            return;
        }
        usageBtn.setVisibility(View.GONE);
        target.setText("Storage: checking…");
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final long[] s = Utils.storageStats(appCtx, e.pkg);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (s == null) {
                            target.setText("Data & cache: not available on this device");
                        } else {
                            target.setText("App " + Utils.humanSize(s[0])
                                    + "  •  Data " + Utils.humanSize(s[1])
                                    + "  •  Cache " + Utils.humanSize(s[2])
                                    + "\nClear cache/data: use the Shizuku power tools below");
                        }
                    }
                });
            }
        });
    }

    // --------------------------------------------------------------- shizuku

    private void initShizuku() {
        shizukuStateListener = new ShizukuHelper.StateListener() {
            @Override
            public void onShizukuStateChanged() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateShizukuIndicator();
                    }
                });
            }
        };
        ShizukuHelper.addStateListener(shizukuStateListener);
        try {
            shizukuBinderListener = new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateShizukuIndicator();
                        }
                    });
                }
            };
            shizukuDeadListener = new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    ShizukuHelper.onBinderDead();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateShizukuIndicator();
                        }
                    });
                }
            };
            shizukuPermListener = new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuHelper.REQUEST_CODE) return;
                    final boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ShizukuHelper.notifyPermissionChanged();
                            Utils.toast(MainActivity.this,
                                    granted ? "Shizuku permission granted" : "Shizuku permission denied");
                            updateShizukuIndicator();
                            Runnable action = pendingShizukuAction;
                            pendingShizukuAction = null;
                            if (granted && action != null) action.run();
                        }
                    });
                }
            };
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
            Shizuku.addBinderDeadListener(shizukuDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
    }

    private void updateShizukuIndicator() {
        if (dotShizuku == null || tvShizukuChip == null) return;
        int color;
        String text;
        if (!ShizukuHelper.isRunning()) {
            color = 0xFF9E9E9E;
            text = "Shizuku off";
        } else if (!ShizukuHelper.hasPermission()) {
            color = 0xFFF9A825;
            text = "Shizuku";
        } else {
            color = 0xFF2E7D32;
            text = "Shizuku on";
        }
        Drawable bg = dotShizuku.getBackground();
        if (bg != null) bg.setTint(color);
        tvShizukuChip.setText(text);
        tvShizukuChip.setTextColor(color == 0xFF2E7D32 ? 0xFF2E7D32 : 0xFF6B7280);
    }

    private void withShizuku(final Operation op, final Runnable action) {
        if (op.finished()) return;
        if (!ShizukuHelper.isRunning()) {
            op.done(false, "Shizuku is not running");
            showShizukuNotRunning();
            return;
        }
        if (!ShizukuHelper.hasPermission()) {
            op.log("Requesting Shizuku permission…");
            pendingShizukuAction = new Runnable() {
                @Override
                public void run() {
                    withShizuku(op, action);
                }
            };
            ShizukuHelper.requestPermission();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!ShizukuHelper.hasPermission() && !op.finished()) {
                        op.done(false, "Permission was not granted");
                    }
                }
            }, 60000);
            return;
        }
        op.log("Shizuku ready • " + ShizukuHelper.serverUid() + " • binder mode");
        action.run();
    }

    private interface PowerCall {
        ShizukuHelper.Result run();
    }

    private void powerAction(final AppEntry e, String title, final String label,
                             final String success, final Runnable after,
                             final PowerCall call) {
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        if (!ShizukuHelper.isRunning()) {
            showShizukuNotRunning();
            return;
        }
        final Operation op = new Operation(title);
        activeOperation = op;
        withShizuku(op, new Runnable() {
            @Override
            public void run() {
                op.log("$ " + label);
                setBusy(true, "Shizuku: " + label);
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        final ShizukuHelper.Result r = call.run();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false, null);
                                if (r.ok()) {
                                    op.done(true, success);
                                    if (after != null) ui.postDelayed(after, 800);
                                } else {
                                    op.done(false, r.message());
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void showShizukuStatus() {
        View v = getLayoutInflater().inflate(R.layout.dialog_shizuku, null);
        final TextView state = v.findViewById(R.id.sh_state);
        final TextView detail = v.findViewById(R.id.sh_detail);
        final Button open = v.findViewById(R.id.sh_open);
        final Button grant = v.findViewById(R.id.sh_grant);
        final Button restart = v.findViewById(R.id.sh_restart);
        final Button test = v.findViewById(R.id.sh_test);
        Button copy = v.findViewById(R.id.sh_copy);
        Button close = v.findViewById(R.id.sh_close);
        test.setText("Test Shizuku (self-test)");

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                boolean running = ShizukuHelper.isRunning();
                boolean granted = running && ShizukuHelper.hasPermission();
                boolean ready = ShizukuHelper.isReady();
                if (!running) {
                    state.setText("Shizuku is not running");
                    state.setTextColor(0xFFC62828);
                    detail.setText("Start Shizuku via Wireless debugging, then come back.\n"
                            + "Shizuku must be restarted after every reboot.");
                } else if (!granted) {
                    state.setText("Permission required");
                    state.setTextColor(0xFFF57C00);
                    detail.setText("Shizuku is running (API " + ShizukuHelper.version()
                            + ").\nGrant AppMan permission to use force stop and cache clearing.");
                } else {
                    state.setText("Ready — no root");
                    state.setTextColor(0xFF2E7D32);
                    detail.setText("Shizuku API " + ShizukuHelper.version()
                            + "  •  server UID " + ShizukuHelper.serverUid()
                            + "\nMode: system-service binder proxy (no user service)\n"
                            + "Force stop, clear cache and clear data are enabled in every app's details.");
                }
                if (!ShizukuHelper.getLastError().isEmpty()) {
                    detail.append("\n\nLast error: " + ShizukuHelper.getLastError());
                }
                grant.setVisibility(running && !granted ? View.VISIBLE : View.GONE);
                restart.setVisibility(View.GONE);
                test.setVisibility(ready ? View.VISIBLE : View.GONE);
            }
        };
        refresh.run();

        final ShizukuHelper.StateListener listener = new ShizukuHelper.StateListener() {
            @Override
            public void onShizukuStateChanged() {
                ui.post(refresh);
            }
        };
        ShizukuHelper.addStateListener(listener);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                ShizukuHelper.removeStateListener(listener);
            }
        });

        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ShizukuHelper.openShizuku(MainActivity.this);
            }
        });
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ShizukuHelper.requestPermission();
            }
        });
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                runTestShizuku();
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Utils.copyText(MainActivity.this, "Shizuku diagnostics",
                        ShizukuHelper.diagnostics(MainActivity.this));
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
            }
        });

        dlg.show();
    }

    private void runTestShizuku() {
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        final Operation op = new Operation("Test Shizuku");
        activeOperation = op;
        withShizuku(op, new Runnable() {
            @Override
            public void run() {
                op.log("Resolving system services…");
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        final ShizukuHelper.Result r = ShizukuHelper.selfTest();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                op.done(r.ok(), r.output);
                            }
                        });
                    }
                });
            }
        });
    }

    private void showShizukuNotRunning() {
        new AlertDialog.Builder(this)
                .setTitle("Shizuku is not running")
                .setMessage("Force stop and cache clearing need Shizuku. Shizuku runs a shell "
                        + "service over Wireless debugging — no root required.\n\n"
                        + "Open Shizuku → \"Start via Wireless debugging\", then try again.")
                .setPositiveButton("Open Shizuku", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ShizukuHelper.openShizuku(MainActivity.this);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class Operation {
        final AlertDialog dialog;
        final TextView logView;
        final TextView status;
        final ProgressBar progress;
        final ScrollView scroll;
        final Button close;
        final StringBuilder sb = new StringBuilder();
        boolean finished;

        Operation(String title) {
            View v = getLayoutInflater().inflate(R.layout.dialog_operation, null);
            ((TextView) v.findViewById(R.id.op_title)).setText(title);
            logView = v.findViewById(R.id.op_log);
            status = v.findViewById(R.id.op_status);
            progress = v.findViewById(R.id.op_progress);
            scroll = v.findViewById(R.id.op_scroll);
            close = v.findViewById(R.id.op_close);
            Button copy = v.findViewById(R.id.op_copy);
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setView(v)
                    .setCancelable(false)
                    .create();
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Utils.copyText(MainActivity.this, "Operation log", sb.toString());
                }
            });
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dialog.dismiss();
                }
            });
            close.setEnabled(false);
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    if (activeOperation == Operation.this) activeOperation = null;
                }
            });
            dialog.show();
        }

        void log(String line) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            sb.append(time).append("  ").append(line).append('\n');
            logView.setText(sb.toString());
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        void done(boolean ok, String message) {
            if (finished) return;
            finished = true;
            progress.setVisibility(View.GONE);
            status.setText(ok ? "Completed" : "Failed");
            status.setTextColor(ok ? 0xFF2E7D32 : 0xFFC62828);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            sb.append(time).append("  ").append(ok ? "✔ " : "✘ ").append(message).append('\n');
            logView.setText(sb.toString());
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
            close.setEnabled(true);
            if (activeOperation == this) activeOperation = null;
            pendingShizukuAction = null;
        }

        boolean finished() {
            return finished;
        }
    }

    // ------------------------------------------------------------------- csv

    private void exportCsv() {
        final List<AppEntry> snapshot = new ArrayList<>(allApps);
        if (snapshot.isEmpty()) {
            Utils.toast(this, "No apps loaded yet");
            return;
        }
        setBusy(true, "Exporting app list…");
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                StringBuilder sb = new StringBuilder(
                        "name,package,version,apk_bytes,type,updated,target_sdk,last_used,screen_time_ms\n");
                for (AppEntry e : snapshot) {
                    sb.append(csv(e.label)).append(',')
                            .append(csv(e.pkg)).append(',')
                            .append(csv(e.versionName)).append(',')
                            .append(e.apkSize).append(',')
                            .append(e.system ? "system" : "user").append(',')
                            .append(csv(Utils.fmtDate(e.updated))).append(',')
                            .append(e.targetSdk).append(',')
                            .append(csv(Utils.fmtDate(e.lastUsed))).append(',')
                            .append(e.totalForeground).append('\n');
                }
                final String fileName = "apps-" + ts + ".csv";
                try {
                    StorageWriter.writeText(appCtx, StorageWriter.ROOT + "/exports",
                            fileName, "text/csv", sb.toString());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            Utils.toast(MainActivity.this,
                                    "Exported to Download/AppMan/exports/" + fileName);
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            Utils.toast(MainActivity.this, "Export failed: " + ex.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // -------------------------------------------------------------- ui helpers

    private void setBusy(boolean busy, String message) {
        statusBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) tvStatus.setText(message);
    }

    private void showProgressDialog(String title, int max) {
        View v = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        pTitle = v.findViewById(R.id.p_title);
        pMsg = v.findViewById(R.id.p_msg);
        pBar = v.findViewById(R.id.p_bar);
        pTitle.setText(title);
        pBar.setMax(Math.max(max, 1));
        pBar.setProgress(0);
        pMsg.setText("Preparing…");
        progressDialog = new AlertDialog.Builder(this)
                .setView(v)
                .setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cancelBackup = true;
                    }
                })
                .create();
        progressDialog.show();
    }

    private void hideProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
            pTitle = null;
            pMsg = null;
            pBar = null;
        }
    }

    // ------------------------------------------------------- lifecycle / back

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuIndicator();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pkgReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pkgReceiver, f);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try {
                unregisterReceiver(pkgReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelection();
            return;
        }
        if (etSearch.getText().length() > 0) {
            etSearch.setText("");
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingBackup != null) {
                    List<AppEntry> p = pendingBackup;
                    pendingBackup = null;
                    startBackup(p);
                }
            } else {
                pendingBackup = null;
                Utils.toast(this, "Storage permission is needed to save backups");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (shizukuPermListener != null) {
                Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
            }
            if (shizukuBinderListener != null) {
                Shizuku.removeBinderReceivedListener(shizukuBinderListener);
            }
            if (shizukuDeadListener != null) {
                Shizuku.removeBinderDeadListener(shizukuDeadListener);
            }
            if (shizukuStateListener != null) {
                ShizukuHelper.removeStateListener(shizukuStateListener);
            }
        } catch (Throwable ignored) {
        }
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
