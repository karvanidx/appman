package io.github.karvanidx.appman;

import android.app.Activity;
import android.app.usage.UsageStats;
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
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
    private long loadToken;

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

        ListView list = findViewById(R.id.an_list);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Utils.openAppInfo(AnalyticsActivity.this, rows.get(position).pkg);
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

        Spinner spinner = findViewById(R.id.an_range);
        ArrayAdapter<String> sp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Today", "Last 7 days", "Last 30 days"});
        sp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(sp);
        spinner.setSelection(range);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == range) return;
                range = position;
                load();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
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
            tvApps.setText("—");
            tvLaunches.setText("—");
            progressBar.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText("Usage access is required to show screen time,\nlaunch counts and last-used times.");
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

        io.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, UsageStats> usage = UsageRepo.query(
                        getApplicationContext(), begin, now);
                Map<String, Integer> launchesByPkg = UsageRepo.launchCounts(
                        getApplicationContext(), begin, now);
                final List<Row> result = new ArrayList<>();
                long totalTime = 0;
                long totalLaunches = 0;
                int usedApps = 0;
                for (Map.Entry<String, UsageStats> entry : usage.entrySet()) {
                    UsageStats us = entry.getValue();
                    long fg = us.getTotalTimeInForeground();
                    Integer lc = launchesByPkg.get(entry.getKey());
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
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken || isFinishing()) return;
                        progressBar.setVisibility(View.GONE);
                        rows.clear();
                        rows.addAll(result);
                        long max = 1;
                        for (Row r : result) max = Math.max(max, r.foreground);
                        adapter.setMax(max);
                        adapter.notifyDataSetChanged();
                        tvTime.setText(Utils.humanDuration(fTotalTime));
                        tvApps.setText(String.valueOf(fUsedApps));
                        tvLaunches.setText(String.valueOf(fTotalLaunches));
                        if (rows.isEmpty()) {
                            emptyView.setVisibility(View.VISIBLE);
                            emptyText.setText("No usage recorded for this period.");
                        }
                    }
                });
            }
        });
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
