package io.github.karvanidx.appman;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private ImageButton btnFilter;
    private ListView listView;
    private View emptyView;
    private View bottomBar;
    private View statusBar;
    private View bottomNav;
    private View dotShizuku;
    private TextView tvShowing;
    private TextView tvTotal;
    private TextView tvStatus;
    private TextView tvSelected;
    private TextView tvShizukuChip;
    private Button btnShareSel;
    private ProgressBar progressBar;

    private View navApps;
    private View navAnalytics;
    private View navTools;
    private View navAppsPill;
    private View navAnalyticsPill;
    private View navToolsPill;
    private ImageView navAppsIcon;
    private ImageView navAnalyticsIcon;
    private ImageView navToolsIcon;
    private TextView navAppsLabel;
    private TextView navAnalyticsLabel;
    private TextView navToolsLabel;

    private AlertDialog progressDialog;
    private TextView pTitle;
    private TextView pMsg;
    private ProgressBar pBar;

    private List<AppEntry> pendingBackup;
    private volatile boolean cancelBackup;
    private final ArrayDeque<String> uninstallQueue = new ArrayDeque<>();
    private String currentUninstallPkg;

    private Runnable pendingShizukuAction;
    private Operation activeOperation;
    private Runnable detailsRefresh;
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
        btnFilter = findViewById(R.id.btn_filter);
        listView = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        bottomBar = findViewById(R.id.bottom_bar);
        statusBar = findViewById(R.id.status_bar);
        bottomNav = findViewById(R.id.bottom_nav);
        dotShizuku = findViewById(R.id.dot_shizuku);
        tvShowing = findViewById(R.id.tv_showing);
        tvTotal = findViewById(R.id.tv_total);
        tvStatus = findViewById(R.id.tv_status);
        tvSelected = findViewById(R.id.tv_selected);
        tvShizukuChip = findViewById(R.id.tv_shizuku_chip);
        btnShareSel = findViewById(R.id.btn_share_sel);
        progressBar = findViewById(R.id.progress);

        adapter = new AppAdapter(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        setupListeners();
        setupBottomNav(true);
        initShizuku();
        updateShizukuIndicator();
        loadApps();
        if (getIntent() != null && getIntent().getBooleanExtra("open_tools", false)) {
            getIntent().removeExtra("open_tools");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showTools();
                }
            }, 350);
        }
    }

    private void setupListeners() {
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etSearch.setText("");
            }
        });
        tip(findViewById(R.id.btn_clear), "Clear the search box");
        tip(btnFilter, "Filter and sort apps");
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterDialog();
            }
        });
        tip(findViewById(R.id.nav_apps), "Apps");
        tip(findViewById(R.id.nav_analytics), "Usage analytics");
        tip(findViewById(R.id.nav_tools), "Tools and settings");
        tip(findViewById(R.id.shizuku_chip), "Shizuku status, permission and self-test");
        tip(findViewById(R.id.btn_backup_sel), "Back up selected APKs");
        tip(findViewById(R.id.btn_share_sel), "Share the selected APK");
        tip(findViewById(R.id.btn_uninstall_sel), "Uninstall selected apps");
        tip(findViewById(R.id.btn_power_sel), "Batch Shizuku actions: force stop / clear cache / clear data");
        tip(findViewById(R.id.btn_cancel_sel), "Exit selection mode");
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
        findViewById(R.id.btn_power_sel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBatchPower(v);
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
        tvShowing.setText("Scanning…");
        tvTotal.setText("");
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
            if (filterMode == 3 && !e.running) continue;
            if (filterMode == 4 && !SafetyDb.isDebloatCandidate(this, e.pkg)) continue;
            if (!q.isEmpty() && !e.searchText.contains(q)) continue;
            shown.add(e);
            total += e.apkSize;
        }
        Collections.sort(shown, comparator());
        adapter.setItems(shown);
        adapter.setSelectionState(selectionMode, selected);
        String label;
        switch (filterMode) {
            case 1: label = "Showing User Apps"; break;
            case 2: label = "Showing System Apps"; break;
            case 3: label = "Showing Running Apps"; break;
            case 4: label = "Showing Debloat Candidates"; break;
            default: label = "Showing All Apps"; break;
        }
        tvShowing.setText(label);
        tvTotal.setText(Utils.humanSize(total) + "  •  " + shown.size() + " apps");
        etSearch.setHint("Search " + allApps.size() + " apps…");
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
        if (bottomNav != null) bottomNav.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
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

    // ------------------------------------------------------------------ tools

    private void setupBottomNav(final boolean appsActive) {
        navApps = findViewById(R.id.nav_apps);
        navAnalytics = findViewById(R.id.nav_analytics);
        navTools = findViewById(R.id.nav_tools);
        navAppsPill = findViewById(R.id.nav_apps_pill);
        navAnalyticsPill = findViewById(R.id.nav_analytics_pill);
        navToolsPill = findViewById(R.id.nav_tools_pill);
        navAppsIcon = findViewById(R.id.nav_apps_icon);
        navAnalyticsIcon = findViewById(R.id.nav_analytics_icon);
        navToolsIcon = findViewById(R.id.nav_tools_icon);
        navAppsLabel = findViewById(R.id.nav_apps_label);
        navAnalyticsLabel = findViewById(R.id.nav_analytics_label);
        navToolsLabel = findViewById(R.id.nav_tools_label);

        styleNavItem(navAppsPill, navAppsIcon, navAppsLabel, appsActive);
        styleNavItem(navAnalyticsPill, navAnalyticsIcon, navAnalyticsLabel, !appsActive);
        styleNavItem(navToolsPill, navToolsIcon, navToolsLabel, false);

        navApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (appsActive) {
                    listView.smoothScrollToPosition(0);
                } else {
                    startActivity(new Intent(MainActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                }
            }
        });
        navAnalytics.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (appsActive) {
                    startActivity(new Intent(MainActivity.this, AnalyticsActivity.class));
                }
            }
        });
        navTools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTools();
            }
        });
    }

    private void styleNavItem(View pill, ImageView icon, TextView label, boolean active) {
        if (pill == null) return;
        if (active) {
            pill.setBackgroundResource(R.drawable.bg_nav_active);
            icon.setColorFilter(0xFF2563EB);
            label.setTextColor(0xFF2563EB);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            pill.setBackgroundColor(Color.TRANSPARENT);
            icon.setColorFilter(0xFF6B7280);
            label.setTextColor(0xFF6B7280);
            label.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void showTools() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_tools);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        View.OnClickListener closeThen = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        };
        d.findViewById(R.id.tool_close).setOnClickListener(closeThen);
        d.findViewById(R.id.tool_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                loadApps();
            }
        });
        d.findViewById(R.id.tool_backup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                backupAllUser();
            }
        });
        d.findViewById(R.id.tool_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                exportCsv();
            }
        });
        d.findViewById(R.id.tool_shizuku).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showShizukuStatus();
            }
        });
        d.findViewById(R.id.tool_usage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                Utils.openUsageAccessSettings(MainActivity.this);
            }
        });
        d.findViewById(R.id.tool_safety).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showSafety();
            }
        });
        d.findViewById(R.id.tool_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                showAbout();
            }
        });
        d.show();
        if (w != null) {
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showSafety() {
        int total = SafetyDb.count(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Safety & debloat")
                .setMessage("AppMan has no INTERNET permission and no ads or telemetry.\n\n"
                        + "• " + total + " HyperOS packages in AppMan's own safety list\n"
                        + "• Critical system packages are protected from uninstall\n"
                        + "• Categories are advisory: Recommended / Advanced / Expert\n"
                        + "• Force stop is temporary and does not delete data\n"
                        + "• Backups stay local in Download/AppMan\n\n"
                        + "Verify every package before removing it. Link to UAD-ng for extra context.")
                .setPositiveButton("Show debloat apps", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        filterMode = 4;
                        applyFilter();
                    }
                })
                .setNeutralButton("UAD-ng", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                    "https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation")));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("Close", null);
        b.show();
    }

    private void showFilterDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_filter, null);
        final TextView[] filterChips = {
                v.findViewById(R.id.chip_all),
                v.findViewById(R.id.chip_user),
                v.findViewById(R.id.chip_system),
                v.findViewById(R.id.chip_running),
                v.findViewById(R.id.chip_debloat)
        };
        final TextView[] sortChips = {
                v.findViewById(R.id.chip_name),
                v.findViewById(R.id.chip_size),
                v.findViewById(R.id.chip_updated),
                v.findViewById(R.id.chip_package),
                v.findViewById(R.id.chip_recent)
        };
        final int[] pickFilter = {filterMode};
        final int[] pickSort = {sortMode};

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < filterChips.length; i++) {
                    styleChip(filterChips[i], i == pickFilter[0]);
                }
                for (int i = 0; i < sortChips.length; i++) {
                    styleChip(sortChips[i], i == pickSort[0]);
                }
            }
        };
        for (int i = 0; i < filterChips.length; i++) {
            final int idx = i;
            filterChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickFilter[0] = idx;
                    refresh.run();
                }
            });
        }
        for (int i = 0; i < sortChips.length; i++) {
            final int idx = i;
            sortChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickSort[0] = idx;
                    refresh.run();
                }
            });
        }
        refresh.run();

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        v.findViewById(R.id.filter_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
            }
        });
        v.findViewById(R.id.filter_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                filterMode = pickFilter[0];
                sortMode = pickSort[0];
                if (filterMode == 3 && !Utils.usageAccessGranted(MainActivity.this)) {
                    Utils.toast(MainActivity.this,
                            "Running filter uses usage access");
                } else if (filterMode == 3 && !ShizukuHelper.isReady()) {
                    Utils.toast(MainActivity.this,
                            "Background apps need Shizuku; showing foreground apps only");
                }
                if (sortMode == 4 && !Utils.usageAccessGranted(MainActivity.this)
                        && !recentSortWarned) {
                    recentSortWarned = true;
                    Utils.toast(MainActivity.this,
                            "Recent sort needs usage access");
                }
                dlg.dismiss();
                applyFilter();
            }
        });
        dlg.show();
    }

    private void styleChip(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF374151);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("open_tools", false)) {
            intent.removeExtra("open_tools");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showTools();
                }
            });
        }
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
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String html = "<b>AppMan " + version + "</b><br><br>"
                + "A lightweight no-root app manager and usage tracker, built in Termux.<br><br>"
                + "• Lists all installed apps (user + system)<br>"
                + "• Backup APKs (including split APKs)<br>"
                + "• Export APKs + native libs as a zip<br>"
                + "• Share APK files<br>"
                + "• Uninstall via Android dialog or silently via Shizuku<br>"
                + "• Screen time, launches and session history<br>"
                + "• Force stop / clear cache / clear data via Shizuku<br><br>"
                + "Clear cache &amp; data of other apps needs Shizuku (shell access over "
                + "Wireless debugging, no root).<br><br>"
                + "Built with <a href=\"https://pi.dev/\">Pi agent</a> + "
                + "<a href=\"https://www.deepseek.com/en/news/deepseek-v4-1-flash/\">DeepSeek Flash</a>.<br>"
                + "<a href=\"https://github.com/karvanidx/appman\">github.com/karvanidx/appman</a>";
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("About AppMan")
                .setMessage(android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton("OK", null)
                .create();
        d.show();
        TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) {
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
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
                                        .setPositiveButton("Open Downloads", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                openDownloads();
                                            }
                                        })
                                        .setNegativeButton("Close", null)
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

    private boolean isPackageInstalled(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showBlocked(String pkg) {
        SafetyDb.Entry se = SafetyDb.get(this, pkg);
        String label = se != null ? se.title : pkg;
        new AlertDialog.Builder(this)
                .setTitle("Safety guard")
                .setMessage("AppMan protects critical system packages.\n\n" + label
                        + " is on the protected list, so uninstall and clear data are blocked.\n\n"
                        + "Force stop is still allowed.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmUninstall(List<String> pkgs) {
        if (pkgs.isEmpty()) return;
        for (String p : pkgs) {
            if (SafetyDb.isBlocked(this, p)) {
                showBlocked(p);
                return;
            }
        }
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        String title;
        String message;
        if (pkgs.size() == 1) {
            AppEntry e = byPkg.get(pkgs.get(0));
            title = "Uninstall";
            message = "Uninstall " + (e != null ? e.label : pkgs.get(0)) + "?";
        } else {
            title = "Uninstall " + pkgs.size() + " apps";
            message = "Uninstall these " + pkgs.size() + " apps?";
        }
        SafetyDb.Entry se = pkgs.size() == 1 ? SafetyDb.get(this, pkgs.get(0)) : null;
        if (se != null) {
            message += "\n\nSafety: " + SafetyDb.label(se.category) + " — " + se.note;
        } else if (pkgs.size() > 1) {
            message += "\n\nSafety: mixed/unknown apps. Back up before removing.";
        }
        boolean ready = ShizukuHelper.isReady();
        message += ready
                ? "\n\nVia Shizuku: backs up the APK first, then uninstalls silently "
                + "(no system popup, live log). System apps may fail."
                + "\nAndroid dialog: the normal system confirmation."
                : "\n\nShizuku is not ready, so only the Android confirmation dialog is available.";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null);
        if (ready) {
            b.setPositiveButton("Via Shizuku", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    uninstallViaShizuku(pkgs);
                }
            });
            b.setNeutralButton("Android dialog", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    requestUninstall(pkgs);
                }
            });
        } else {
            b.setPositiveButton("Android dialog", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    requestUninstall(pkgs);
                }
            });
        }
        b.show();
    }

    private void uninstallViaShizuku(final List<String> pkgs) {
        if (!ShizukuHelper.isReady()) {
            showShizukuStatus();
            return;
        }
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        final Operation op = new Operation("Uninstall " + pkgs.size()
                + (pkgs.size() == 1 ? " app" : " apps"));
        activeOperation = op;
        withShizuku(op, new Runnable() {
            @Override
            public void run() {
                if (selectionMode) exitSelection();
                setBusy(true, "Backing up before uninstall…");
                op.log("Backing up APKs before uninstall…");
                final Context appCtx = getApplicationContext();
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        java.util.List<AppEntry> backups = new ArrayList<>();
                        for (String p : pkgs) {
                            AppEntry entry = byPkg.get(p);
                            if (entry != null) backups.add(entry);
                        }
                        if (!backups.isEmpty()) {
                            final String[] loc = {""};
                            BackupManager.backup(appCtx, backups, new BackupManager.Listener() {
                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public void onProgress(int done, int total, String label) {
                                }

                                @Override
                                public void onDone(final int ok, final int failed, final String location) {
                                    loc[0] = location;
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            op.log("Backup: " + ok + " ok"
                                                    + (failed > 0 ? ", " + failed + " failed" : "")
                                                    + "  →  " + location);
                                        }
                                    });
                                }
                            });
                        }
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(true, "Uninstalling " + pkgs.size() + " app(s)");
                                op.log("Uninstalling " + pkgs.size() + " package(s)…");
                            }
                        });
                        int ok = 0;
                        int fail = 0;
                        for (final String pkg : pkgs) {
                            if (pkg.equals(getPackageName())) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("• AppMan itself  (skipped)");
                                    }
                                });
                                continue;
                            }
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    op.log("• " + pkg);
                                }
                            });
                            final ShizukuHelper.Result r = ShizukuHelper.uninstall(pkg);
                            if (r.ok()) {
                                ok++;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("  ✔ uninstalled");
                                    }
                                });
                            } else {
                                fail++;
                                final String msg = r.message();
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("  ✘ " + msg);
                                    }
                                });
                            }
                        }
                        final int fOk = ok;
                        final int fFail = fail;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false, null);
                                op.done(fFail == 0, fOk + " uninstalled"
                                        + (fFail > 0 ? ", " + fFail + " failed" : ""));
                                loadApps();
                            }
                        });
                    }
                });
            }
        });
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
        currentUninstallPkg = pkg;
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
            if (currentUninstallPkg != null) {
                final String pkg = currentUninstallPkg;
                currentUninstallPkg = null;
                Utils.toast(this, isPackageInstalled(pkg)
                        ? "Not uninstalled: " + pkg
                        : "Uninstalled " + pkg);
            }
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

    private void showBatchPower(View anchor) {
        final List<AppEntry> apps = selectedEntries();
        if (apps.isEmpty()) return;
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add(1, 1, 0, "Force stop selected (" + apps.size() + ")");
        p.getMenu().add(1, 2, 1, "Clear cache selected (" + apps.size() + ")");
        p.getMenu().add(1, 3, 2, "Clear data selected (" + apps.size() + ")");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int kind = item.getItemId() - 1;
                if (kind == 2) {
                    confirmBatchClearData(apps);
                } else {
                    runBatchPower(kind, apps);
                }
                return true;
            }
        });
        p.show();
    }

    private void confirmBatchClearData(final List<AppEntry> apps) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < apps.size() && i < 5; i++) {
            if (i > 0) names.append(", ");
            names.append(apps.get(i).label);
        }
        if (apps.size() > 5) names.append(", …");
        new AlertDialog.Builder(this)
                .setTitle("Clear data of " + apps.size() + " apps?")
                .setMessage("Erase ALL data of:\n\n" + names
                        + "\n\nThis cannot be undone.")
                .setPositiveButton("Clear data", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runBatchPower(2, apps);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runBatchPower(final int kind, final List<AppEntry> apps) {
        if (apps.isEmpty()) return;
        if (!ShizukuHelper.isReady()) {
            showShizukuStatus();
            return;
        }
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        final String title = (kind == 0 ? "Force stop" : kind == 1 ? "Clear cache" : "Clear data")
                + " · " + apps.size() + " apps";
        final Operation op = new Operation(title);
        activeOperation = op;
        withShizuku(op, new Runnable() {
            @Override
            public void run() {
                if (selectionMode) exitSelection();
                final String verb = kind == 0 ? "Force-stopping" : kind == 1 ? "Clearing cache of" : "Clearing data of";
                setBusy(true, verb + " " + apps.size() + " apps");
                op.log(verb + " " + apps.size() + " apps…");
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        int ok = 0;
                        int fail = 0;
                        for (final AppEntry e : apps) {
                            if (kind == 2 && SafetyDb.isBlocked(MainActivity.this, e.pkg)) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("• " + e.label + "  (protected, skipped)");
                                    }
                                });
                                continue;
                            }
                            if (e.pkg.equals(getPackageName())) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("• " + e.label + "  (skipped AppMan itself)");
                                    }
                                });
                                continue;
                            }
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    op.log("• " + e.label);
                                }
                            });
                            final ShizukuHelper.Result r = kind == 0
                                    ? ShizukuHelper.forceStop(e.pkg)
                                    : kind == 1
                                    ? ShizukuHelper.clearCache(e.pkg)
                                    : ShizukuHelper.clearData(e.pkg);
                            if (r.ok()) {
                                ok++;
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("  ✔ done");
                                    }
                                });
                            } else {
                                fail++;
                                final String msg = r.message();
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        op.log("  ✘ " + msg);
                                    }
                                });
                            }
                        }
                        final int fOk = ok;
                        final int fFail = fail;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false, null);
                                op.done(fFail == 0, fOk + " succeeded"
                                        + (fFail > 0 ? ", " + fFail + " failed" : ""));
                                loadApps();
                            }
                        });
                    }
                });
            }
        });
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
        Button libs = v.findViewById(R.id.d_libs);
        Button export = v.findViewById(R.id.d_export);

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

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(v)
                .setNegativeButton("Close", null)
                .create();

        tip(v.findViewById(R.id.d_open), "Open this app");
        tip(v.findViewById(R.id.d_backup), "Back up this app's APK");
        tip(v.findViewById(R.id.d_share), "Share this app's APK");
        tip(v.findViewById(R.id.d_appinfo), "Open Android App info (clear cache/data, force stop)");
        tip(v.findViewById(R.id.d_copy), "Copy the package name");
        tip(v.findViewById(R.id.d_uninstall), "Uninstall this app");
        tip(usage, "Grant usage access to show storage and usage stats");
        tip(shizukuStatus, "Shizuku status and setup");
        tip(libs, "List native .so libraries inside this app's APK");
        tip(export, "Export base + split APKs and all native libs as one zip");
        libs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                openNativeLibs(e);
            }
        });
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                exportPackage(e);
            }
        });
        final Button libsButton = libs;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final int count = NativeLibs.list(e).size();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dlg.isShowing()) {
                            libsButton.setText(count == 0
                                    ? "Native libraries (none)"
                                    : "Native libraries (" + count + ")");
                        }
                    }
                });
            }
        });
        loadSizesInto(sizes, usage, e);

        final java.util.Set<Button> cooling = new java.util.HashSet<>();
        final Runnable refreshPower = new Runnable() {
            @Override
            public void run() {
                boolean ready = ShizukuHelper.isReady();
                boolean busy = activeOperation != null && !activeOperation.finished();
                boolean fs = ready && !busy && !cooling.contains(forceStop);
                boolean cc = ready && !busy && !cooling.contains(clearCache);
                boolean cd = ready && !busy && !cooling.contains(clearData);
                forceStop.setEnabled(fs);
                clearCache.setEnabled(cc);
                clearData.setEnabled(cd);
                forceStop.setAlpha(fs ? 1f : 0.42f);
                clearCache.setAlpha(cc ? 1f : 0.42f);
                clearData.setAlpha(cd ? 1f : 0.42f);
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
        detailsRefresh = refreshPower;
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
                if (detailsRefresh == refreshPower) detailsRefresh = null;
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

        tip(forceStop, "Force stop this app via Shizuku");
        tip(clearCache, "Clear this app's cache via Shizuku");
        tip(clearData, "Erase all data of this app via Shizuku");
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
                        }, new Runnable() {
                            @Override
                            public void run() {
                                cooling.add(forceStop);
                                refreshPower.run();
                                ui.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        cooling.remove(forceStop);
                                        refreshPower.run();
                                    }
                                }, 3000);
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                cooling.remove(forceStop);
                                refreshPower.run();
                            }
                        });
                refreshPower.run();
            }
        });
        clearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                powerAction(e, "Clear cache · " + e.label,
                        "deleteApplicationCacheFilesAsUser(" + e.pkg + ")",
                        "Cache clear requested for " + e.label, refreshSizes, new PowerCall() {
                            @Override
                            public ShizukuHelper.Result run() {
                                return ShizukuHelper.clearCache(e.pkg);
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                cooling.add(clearCache);
                                refreshPower.run();
                                ui.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        cooling.remove(clearCache);
                                        refreshPower.run();
                                    }
                                }, 3000);
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                cooling.remove(clearCache);
                                refreshPower.run();
                            }
                        });
                refreshPower.run();
            }
        });
        clearData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (SafetyDb.isBlocked(MainActivity.this, e.pkg)) {
                    showBlocked(e.pkg);
                    return;
                }
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
                                        }, new Runnable() {
                                            @Override
                                            public void run() {
                                                cooling.add(clearData);
                                                refreshPower.run();
                                            }
                                        }, new Runnable() {
                                            @Override
                                            public void run() {
                                                cooling.remove(clearData);
                                                refreshPower.run();
                                            }
                                        });
                                refreshPower.run();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        dlg.show();
    }

    private void exportPackage(final AppEntry e) {
        if (activeOperation != null && !activeOperation.finished()) {
            Utils.toast(this, "Another operation is still running");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Export package?")
                .setMessage("Create a zip with the base APK, split APKs and all native libs for \""
                        + e.label + "\"?\n\nSaved to Download/AppMan/exports/")
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doExport(e);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doExport(final AppEntry e) {
        final Operation op = new Operation("Export package · " + e.label);
        activeOperation = op;
        op.log("Packing base APK, split APKs and native libs…");
        setBusy(true, "Exporting " + e.label);
        final Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File zip = PackageExporter.build(appCtx, e);
                    final long size = zip.length();
                    final String name = zip.getName();
                    final Uri uri = StorageWriter.writeFile(appCtx, StorageWriter.ROOT + "/exports",
                            name, "application/zip", zip);
                    zip.delete();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            op.done(true, name + "  (" + Utils.humanSize(size) + ")\n"
                                    + "Download/AppMan/exports/" + name);
                            op.dialog.dismiss();
                            showExportDone(name, size, uri);
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false, null);
                            op.done(false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void openDownloads() {
        try {
            startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception ex) {
            Utils.toast(this, "Open your Files app → Download/AppMan");
        }
    }

    private void showExportDone(String name, long size, final Uri uri) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Export complete")
                .setMessage(name + "  (" + Utils.humanSize(size) + ")\n\n"
                        + "Download/AppMan/exports/" + name)
                .setPositiveButton("Open Downloads", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openDownloads();
                    }
                })
                .setNegativeButton("Close", null);
        if (uri != null && "content".equals(uri.getScheme())) {
            b.setNeutralButton("Share zip", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/zip");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newRawUri("package zip", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share package zip"));
                }
            });
        }
        b.show();
    }

    private void openNativeLibs(final AppEntry e) {
        View v = getLayoutInflater().inflate(R.layout.dialog_libs, null);
        final TextView text = v.findViewById(R.id.libs_text);
        final TextView sub = v.findViewById(R.id.libs_sub);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        final StringBuilder copy = new StringBuilder();
        sub.setText(e.label + "  •  " + e.pkg + "\nScanning APK…");
        v.findViewById(R.id.libs_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Utils.copyText(MainActivity.this, "Native libraries", copy.toString());
            }
        });
        v.findViewById(R.id.libs_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
            }
        });
        dlg.show();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final java.util.List<NativeLibs.Lib> libs = NativeLibs.list(e);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        sub.setText(e.label + "  •  " + e.pkg + "\n"
                                + libs.size() + " libraries  •  "
                                + Utils.humanSize(NativeLibs.totalSize(libs)));
                        if (libs.isEmpty()) {
                            text.setText("This app has no native .so libraries.");
                            copy.append("No native libraries");
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format(Locale.US, "%-10s %9s  %s\n", "ABI", "SIZE", "LIBRARY"));
                        sb.append("----------------------------------------------\n");
                        for (NativeLibs.Lib l : libs) {
                            String line = String.format(Locale.US, "%-10s %9s  %s", l.abi,
                                    Utils.humanSize(l.size), l.name);
                            sb.append(line).append('\n');
                        }
                        text.setText(sb.toString());
                        copy.append(sub.getText()).append('\n').append(sb);
                    }
                });
            }
        });
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
        int dotColor;
        String text;
        int pill;
        int textColor;
        if (!ShizukuHelper.isRunning()) {
            dotColor = 0xFF9CA3AF;
            text = "SHIZUKU OFF";
            pill = R.drawable.bg_pill_gray;
            textColor = 0xFF6B7280;
        } else if (!ShizukuHelper.hasPermission()) {
            dotColor = 0xFFF59E0B;
            text = "SHIZUKU PERMISSION";
            pill = R.drawable.bg_pill_amber;
            textColor = 0xFFB45309;
        } else {
            dotColor = 0xFF10B981;
            text = "SHIZUKU ON";
            pill = R.drawable.bg_pill_green;
            textColor = 0xFF047857;
        }
        Drawable bg = dotShizuku.getBackground();
        if (bg != null) bg.setTint(dotColor);
        View chip = findViewById(R.id.shizuku_chip);
        if (chip != null) chip.setBackgroundResource(pill);
        tvShizukuChip.setText(text);
        tvShizukuChip.setTextColor(textColor);
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
                             final PowerCall call, final Runnable onSuccess,
                             final Runnable onError) {
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
                                    if (onSuccess != null) onSuccess.run();
                                } else {
                                    op.done(false, r.message());
                                    if (onError != null) onError.run();
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
            Runnable refresh = detailsRefresh;
            if (refresh != null) ui.post(refresh);
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

    private void tip(View v, String text) {
        if (v != null && Build.VERSION.SDK_INT >= 26) {
            v.setTooltipText(text);
        }
    }

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
