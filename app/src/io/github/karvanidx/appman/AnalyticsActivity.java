package io.github.karvanidx.appman;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalyticsActivity extends Activity {
    private static final int RANGE_TODAY = 0;
    private static final int RANGE_WEEK = 1;
    private static final int RANGE_MONTH = 2;

    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private ExecutorService io;
    private Handler ui;
    private PackageManager pm;

    private int range = RANGE_WEEK;
    private TextView tvTime;
    private TextView tvApps;
    private TextView tvLaunches;
    private TextView emptyText;
    private Button grantButton;
    private View emptyView;
    private ProgressBar progressBar;
    private TextView[] rangeButtons;
    private long loadToken;

    private UsageRepo.Analysis lastAnalysis;
    private long lastBegin;
    private long lastEnd;

    static class Row {
        String pkg;
        String label;
        Drawable icon;
        long foreground;
        int launches;
        long lastUsed;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);
        io = Executors.newSingleThreadExecutor();
        ui = new Handler(Looper.getMainLooper());
        pm = getPackageManager();

        tvTime = findViewById(R.id.an_time);
        tvApps = findViewById(R.id.an_apps);
        tvLaunches = findViewById(R.id.an_launches);
        emptyText = findViewById(R.id.an_empty_text);
        grantButton = findViewById(R.id.an_grant);
        emptyView = findViewById(R.id.an_empty);
        progressBar = findViewById(R.id.an_progress);

        rangeButtons = new TextView[]{
                findViewById(R.id.an_range_today),
                findViewById(R.id.an_range_week),
                findViewById(R.id.an_range_month)
        };

        ListView list = findViewById(R.id.an_list);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showSessions(rows.get(position));
            }
        });

        ImageButton back = findViewById(R.id.an_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.an_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.openUsageAccessSettings(AnalyticsActivity.this);
            }
        });

        tip(back, "Back to AppMan");
        tip(findViewById(R.id.an_refresh), "Refresh usage data");
        for (int i = 0; i < rangeButtons.length; i++) {
            final int r = i;
            rangeButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRange(r);
                }
            });
        }
        applyRangeStyles();
    }

    private void tip(View v, String text) {
        if (v != null && Build.VERSION.SDK_INT >= 26) v.setTooltipText(text);
    }

    private void setRange(int r) {
        if (range == r) return;
        range = r;
        applyRangeStyles();
        load();
    }

    private void applyRangeStyles() {
        for (int i = 0; i < rangeButtons.length; i++) {
            boolean selected = i == range;
            rangeButtons[i].setBackgroundResource(selected
                    ? R.drawable.bg_seg_selected : R.drawable.bg_seg_normal);
            rangeButtons[i].setTextColor(selected ? 0xFFFFFFFF : 0xFF1565C0);
            rangeButtons[i].setTypeface(null, selected
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final long token = ++loadToken;
        if (!Utils.usageAccessGranted(this)) {
            rows.clear();
            adapter.notifyDataSetChanged();
            tvTime.setText("—");
            tvApps.setText("— apps used");
            tvLaunches.setText("— launches");
            progressBar.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText("Usage access is required to show screen time,\nlaunch counts and session history.");
            grantButton.setVisibility(View.VISIBLE);
            return;
        }

        grantButton.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        long now = System.currentTimeMillis();
        final long begin;
        if (range == RANGE_TODAY) {
            begin = UsageRepo.startOfToday();
        } else if (range == RANGE_WEEK) {
            begin = now - 7L * 24 * 60 * 60 * 1000;
        } else {
            begin = now - 30L * 24 * 60 * 60 * 1000;
        }

        final long fBegin = begin;
        final long fEnd = now;
        final int fRange = range;
        final android.content.Context appCtx = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, UsageStats> usage = UsageRepo.query(appCtx, fBegin, fEnd);
                UsageRepo.Analysis analysis = UsageRepo.analyze(appCtx, fBegin, fEnd);
                final List<Row> result = new ArrayList<>();
                long totalTime = 0;
                long totalLaunches = 0;
                int usedApps = 0;
                for (Map.Entry<String, UsageStats> entry : usage.entrySet()) {
                    UsageStats us = entry.getValue();
                    long fg = us.getTotalTimeInForeground();
                    Integer lc = analysis.launches.get(entry.getKey());
                    int launches = lc == null ? 0 : lc;
                    long last = us.getLastTimeUsed();
                    if (fg <= 0 && launches <= 0) continue;
                    Row r = new Row();
                    r.pkg = entry.getKey();
                    try {
                        r.label = String.valueOf(pm.getApplicationLabel(
                                pm.getApplicationInfo(r.pkg, 0)));
                        r.icon = pm.getApplicationIcon(r.pkg);
                    } catch (Exception e) {
                        continue;
                    }
                    r.foreground = fg;
                    r.launches = launches;
                    r.lastUsed = last;
                    result.add(r);
                    totalTime += fg;
                    totalLaunches += launches;
                    if (fg > 0) usedApps++;
                }
                Collections.sort(result, new Comparator<Row>() {
                    @Override
                    public int compare(Row a, Row b) {
                        int c = Long.compare(b.foreground, a.foreground);
                        return c != 0 ? c : Long.compare(b.lastUsed, a.lastUsed);
                    }
                });
                final long fTotalTime = totalTime;
                final int fUsedApps = usedApps;
                final long fTotalLaunches = totalLaunches;
                final UsageRepo.Analysis fAnalysis = analysis;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken || isFinishing()) return;
                        lastAnalysis = fAnalysis;
                        lastBegin = fBegin;
                        lastEnd = fEnd;
                        progressBar.setVisibility(View.GONE);
                        rows.clear();
                        rows.addAll(result);
                        long max = 1;
                        for (Row r : result) max = Math.max(max, r.foreground);
                        adapter.setMax(max);
                        adapter.notifyDataSetChanged();
                        tvTime.setText(Utils.humanDuration(fTotalTime));
                        tvApps.setText(fUsedApps + " apps used");
                        tvLaunches.setText(fTotalLaunches + " launches");
                        if (rows.isEmpty()) {
                            emptyView.setVisibility(View.VISIBLE);
                            emptyText.setText("No usage recorded for this period.");
                        }
                    }
                });
            }
        });
    }

    private void showSessions(Row row) {
        View v = getLayoutInflater().inflate(R.layout.dialog_sessions, null);
        TextView title = v.findViewById(R.id.ses_title);
        TextView sub = v.findViewById(R.id.ses_sub);
        TextView text = v.findViewById(R.id.ses_text);
        title.setText(row.label);

        List<UsageRepo.Session> sessions = lastAnalysis == null ? null
                : lastAnalysis.sessions.get(row.pkg);
        if (sessions == null) sessions = new ArrayList<>();

        long total = 0;
        for (UsageRepo.Session s : sessions) total += s.duration();
        String summary = Utils.humanDuration(total) + "  •  " + sessions.size()
                + " session" + (sessions.size() == 1 ? "" : "s");
        if (row.lastUsed > 0) summary += "  •  last used " + Utils.ago(row.lastUsed);
        sub.setText(summary);

        if (sessions.isEmpty()) {
            text.setText("No session events for this range.\n\n"
                    + "Android keeps UsageEvents only for a limited time; "
                    + "try the 7-day range or use the app and come back.");
        } else {
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            SimpleDateFormat dateFmt = new SimpleDateFormat("MMM d", Locale.US);
            boolean showDate = range != RANGE_TODAY;
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(sessions.size(), 250);
            for (int i = 0; i < limit; i++) {
                UsageRepo.Session s = sessions.get(i);
                StringBuilder line = new StringBuilder();
                if (showDate) line.append(dateFmt.format(new Date(s.start))).append("  ");
                line.append(timeFmt.format(new Date(s.start))).append(" → ")
                        .append(s.ongoing ? "ongoing  " : timeFmt.format(new Date(s.end)) + "  ")
                        .append(Utils.humanDuration(s.duration()));
                sb.append(line).append('\n');
            }
            if (sessions.size() > limit) {
                sb.append("… and ").append(sessions.size() - limit).append(" more\n");
            }
            text.setText(sb.toString());
        }

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        v.findViewById(R.id.ses_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
                Utils.openAppInfo(AnalyticsActivity.this, row.pkg);
            }
        });
        v.findViewById(R.id.ses_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dlg.dismiss();
            }
        });
        dlg.show();
    }

    private class RowAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(AnalyticsActivity.this);
        private long max = 1;

        void setMax(long max) {
            this.max = Math.max(1, max);
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.row_usage, parent, false);
            }
            Row r = rows.get(position);
            ImageView icon = convertView.findViewById(R.id.u_icon);
            TextView name = convertView.findViewById(R.id.u_name);
            TextView sub = convertView.findViewById(R.id.u_sub);
            TextView time = convertView.findViewById(R.id.u_time);
            ProgressBar bar = convertView.findViewById(R.id.u_bar);

            icon.setImageDrawable(r.icon != null ? r.icon
                    : getDrawable(android.R.drawable.sym_def_app_icon));
            name.setText(r.label);
            StringBuilder s = new StringBuilder();
            s.append(r.launches).append(" launches");
            if (r.lastUsed > 0) s.append("  •  ").append(Utils.ago(r.lastUsed));
            sub.setText(s);
            time.setText(Utils.humanDuration(r.foreground));
            bar.setProgress(r.foreground <= 0 ? 0
                    : (int) Math.max(1, r.foreground * 1000 / Math.max(max, 1)));
            return convertView;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }
}
