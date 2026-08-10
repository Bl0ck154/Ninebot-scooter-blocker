package com.bl0ck154.ninebotblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Native vertically scrolling per-scooter ride history and calendar statistics. */
public final class StatsActivity extends Activity implements ScooterRepository.Listener {
    private static final int REQ_EXPORT = 91;
    private static final int REQ_IMPORT = 92;
    private static final long SUMMARY_REFRESH_MS = 5000L;

    private static final int BG = Color.rgb(245, 247, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(17, 24, 39);
    private static final int MUTED = Color.rgb(102, 112, 133);
    private static final int BORDER = Color.rgb(226, 232, 240);
    private static final int ACCENT = Color.rgb(0, 126, 121);
    private static final int ACCENT_SOFT = Color.rgb(229, 247, 245);

    private ScooterRepository repository;
    private RideStatsStore store;
    private int selectedPeriod = RideStatsTracker.PERIOD_DAY;
    private long selectedAnchorMs;
    private long lastSummaryRefreshAt;

    private TextView modelText;
    private TextView connectionText;
    private TextView currentDistance;
    private TextView currentMeta;
    private TextView endRideAction;
    private TextView continueAction;
    private TextView dayTab;
    private TextView weekTab;
    private TextView monthTab;
    private TextView previousPeriod;
    private TextView nextPeriod;
    private TextView periodTitle;
    private TextView summaryDistance;
    private TextView summaryRides;
    private TextView summaryTime;
    private TextView summarySpeed;
    private TextView summaryUsed;
    private TextView summaryCharged;
    private TextView ridesText;
    private TextView pauseValue;
    private RideHeatmapView heatmap;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        repository = ScooterRepository.get(this);
        store = RideStatsStore.get(this);
        selectedAnchorMs = startOfDay(System.currentTimeMillis());
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        lastSummaryRefreshAt = 0L;
        repository.addListener(this);
        repository.setUiActive(true);
    }

    @Override protected void onStop() {
        repository.removeListener(this);
        repository.setUiActive(false);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(18), top = dp(16), bottom = dp(28);
        root.setPadding(side, top, side, bottom);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(side, top + bars.top, side, bottom + bars.bottom);
                return insets;
            });
        }
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView back = text("‹  SCOOTER", 12, ACCENT);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setPadding(0, dp(6), 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back, full(0));

        TextView heading = text("Statistics", 29, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading, full(dp(2)));

        modelText = text("Ninebot / Segway Scooter", 13, MUTED);
        root.addView(modelText, full(dp(5)));

        connectionText = text("Disconnected", 12, MUTED);
        connectionText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(connectionText, full(dp(14)));

        buildCurrentRide(root);
        buildHeatmap(root);
        buildPeriodControls(root);
        buildMetrics(root);
        buildRides(root);
        buildRideSplit(root);
        buildDataActions(root);

        TextView note = text(
                "Statistics stay on this phone and are separated by scooter serial/MAC. " +
                        "A ride now ends after the selected amount of time without actual movement, " +
                        "even if Bluetooth remains connected.", 11, MUTED);
        note.setLineSpacing(dp(2), 1f);
        root.addView(note, full(0));

        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets();
    }

    private void buildCurrentRide(LinearLayout root) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(label("CURRENT RIDE"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        currentDistance = text("0.00 km", 36, TEXT);
        currentDistance.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(currentDistance, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        endRideAction = actionText("End ride");
        endRideAction.setOnClickListener(v -> {
            if (repository.endCurrentRide()) {
                lastSummaryRefreshAt = 0L;
                render(repository.snapshot(), true);
                Toast.makeText(this, "Ride ended", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(endRideAction);
        c.addView(row, fullTop(dp(2)));

        currentMeta = text("Starts when the scooter moves", 13, MUTED);
        c.addView(currentMeta, fullTop(dp(3)));

        continueAction = actionText("↩ Continue previous ride");
        continueAction.setPadding(dp(10), dp(8), dp(10), dp(8));
        continueAction.setVisibility(View.GONE);
        continueAction.setOnClickListener(v -> {
            if (repository.continuePreviousRide()) {
                lastSummaryRefreshAt = 0L;
                render(repository.snapshot(), true);
                Toast.makeText(this, "Previous ride continued", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "End the active ride first", Toast.LENGTH_SHORT).show();
            }
        });
        c.addView(continueAction, fullTop(dp(10)));
        root.addView(c, full(dp(10)));
    }

    private void buildHeatmap(LinearLayout root) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(label("RIDING ACTIVITY"));
        TextView hint = text("Tap a square to inspect that day", 12, MUTED);
        c.addView(hint, fullTop(dp(3)));

        heatmap = new RideHeatmapView(this);
        heatmap.setPadding(0, dp(4), 0, 0);
        heatmap.setListener(timestamp -> {
            selectedAnchorMs = startOfDay(timestamp);
            selectedPeriod = RideStatsTracker.PERIOD_DAY;
            lastSummaryRefreshAt = 0L;
            refreshSummary();
        });
        c.addView(heatmap, fullTop(dp(6)));

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        TextView less = text("Less", 10, MUTED);
        legend.addView(less);
        int[] colors = {0xFFE8EDF2, 0xFFD5EEE9, 0xFF9DD9D1, 0xFF4FB7AA, 0xFF007E79};
        for (int color : colors) {
            View square = new View(this);
            square.setBackground(rounded(color, 2));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(10), dp(10));
            p.setMargins(dp(3), 0, 0, 0);
            legend.addView(square, p);
        }
        TextView more = text("More", 10, MUTED);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        moreParams.setMargins(dp(5), 0, 0, 0);
        legend.addView(more, moreParams);
        c.addView(legend, fullTop(dp(4)));
        root.addView(c, full(dp(10)));
    }

    private void buildPeriodControls(LinearLayout root) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(stroked(CARD, BORDER, 13));
        dayTab = periodTab("Day", RideStatsTracker.PERIOD_DAY);
        weekTab = periodTab("Week", RideStatsTracker.PERIOD_WEEK);
        monthTab = periodTab("Month", RideStatsTracker.PERIOD_MONTH);
        tabs.addView(dayTab, weightedNoMargin());
        tabs.addView(weekTab, weightedNoMargin());
        tabs.addView(monthTab, weightedNoMargin());
        root.addView(tabs, full(dp(8)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        previousPeriod = navArrow("‹");
        previousPeriod.setOnClickListener(v -> shiftPeriod(-1));
        nextPeriod = navArrow("›");
        nextPeriod.setOnClickListener(v -> shiftPeriod(1));
        periodTitle = text("Today", 15, TEXT);
        periodTitle.setGravity(Gravity.CENTER);
        periodTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        nav.addView(previousPeriod, new LinearLayout.LayoutParams(dp(44), dp(42)));
        nav.addView(periodTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        nav.addView(nextPeriod, new LinearLayout.LayoutParams(dp(44), dp(42)));
        root.addView(nav, full(dp(8)));
    }

    private void buildMetrics(LinearLayout root) {
        TextView[] first = metricPair(root, "DISTANCE", "RIDES");
        summaryDistance = first[0];
        summaryRides = first[1];
        TextView[] second = metricPair(root, "CONNECTED", "MAX SPEED");
        summaryTime = second[0];
        summarySpeed = second[1];
        TextView[] third = metricPair(root, "BATTERY USED", "CHARGED");
        summaryUsed = third[0];
        summaryCharged = third[1];
    }

    private void buildRides(LinearLayout root) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(label("RIDES IN PERIOD"));
        ridesText = text("No rides", 13, TEXT);
        ridesText.setLineSpacing(dp(3), 1f);
        c.addView(ridesText, fullTop(dp(6)));
        root.addView(c, full(dp(10)));
    }

    private void buildRideSplit(LinearLayout root) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(label("RIDE SPLIT"));
        pauseValue = text("20 min without movement  ›", 15, TEXT);
        pauseValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pauseValue.setPadding(0, dp(7), 0, dp(4));
        pauseValue.setOnClickListener(v -> choosePauseTimeout());
        c.addView(pauseValue);
        TextView explanation = text(
                "Short shop/order stops stay in the same ride. When the scooter has not actually moved for this long, the next movement starts a new ride — even if BLE never disconnected.",
                11, MUTED);
        explanation.setLineSpacing(dp(2), 1f);
        c.addView(explanation);
        root.addView(c, full(dp(10)));
    }

    private void buildDataActions(LinearLayout root) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.addView(label("DATA"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView export = actionText("Export JSON");
        export.setGravity(Gravity.CENTER);
        export.setOnClickListener(v -> exportStats());
        TextView importAction = actionText("Import JSON");
        importAction.setGravity(Gravity.CENTER);
        importAction.setOnClickListener(v -> importStats());
        row.addView(export, weighted(true));
        row.addView(importAction, weighted(false));
        c.addView(row, fullTop(dp(8)));
        root.addView(c, full(dp(10)));
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot, false));
    }

    private void render(ScooterRepository.Snapshot snapshot, boolean forceSummary) {
        modelText.setText(snapshot.modelName == null ? "Ninebot / Segway Scooter" : snapshot.modelName);
        boolean connected = snapshot.connectionState == ScooterConnectionState.READY
                && snapshot.telemetry.isConnected();
        connectionText.setText(connected ? "🟢 Connected" : "🔴 " + prettyState(snapshot.connectionState));
        connectionText.setTextColor(connected ? ACCENT : MUTED);

        RideStatsTracker.Snapshot stats = snapshot.rideStats;
        RideStatsStore.RideRecord ride = stats == null ? null : stats.currentRide;
        if (ride == null) {
            currentDistance.setText("0.00 km");
            currentMeta.setText(connected ? "Starts when the scooter moves" : "No active ride");
            endRideAction.setVisibility(View.GONE);
        } else {
            currentDistance.setText(f("%.2f km", stats.currentDistanceKm()));
            currentMeta.setText(formatDuration(ride.elapsedMs(System.currentTimeMillis()))
                    + "  ·  " + batteryDelta(ride)
                    + "  ·  max " + f("%.1f km/h", ride.maxSpeedKmh));
            endRideAction.setVisibility(View.VISIBLE);
        }
        continueAction.setVisibility(stats != null && stats.canContinuePrevious ? View.VISIBLE : View.GONE);
        pauseValue.setText((stats == null ? 20 : stats.pauseMinutes) + " min without movement  ›");

        long now = System.currentTimeMillis();
        if (forceSummary || now - lastSummaryRefreshAt >= SUMMARY_REFRESH_MS) {
            refreshSummary();
            lastSummaryRefreshAt = now;
        }
    }

    private void refreshSummary() {
        String key = repository.scooterKey();
        DateRange range = rangeFor(selectedPeriod, selectedAnchorMs);
        RideStatsStore.PeriodSummary period = key == null
                ? new RideStatsStore.PeriodSummary(0, 0, 0, 0, 0, 0)
                : store.period(key, RideStatsStore.dayKey(range.startMs), RideStatsStore.dayKey(range.endInclusiveMs));

        summaryDistance.setText(f("%.2f km", period.distanceKm));
        summaryRides.setText(String.valueOf(period.rides));
        summaryTime.setText(formatDuration(period.connectedMs));
        summarySpeed.setText(f("%.1f km/h", period.maxSpeedKmh));
        summaryUsed.setText(f("-%.0f%%", period.dischargedPercent));
        summaryCharged.setText(f("+%.0f%%", period.chargedPercent));

        periodTitle.setText(formatRangeTitle(range));
        updatePeriodTabs();
        updateNextArrow();

        List<RideStatsStore.RideRecord> rides = key == null
                ? java.util.Collections.emptyList()
                : store.ridesBetween(key, range.startMs, range.endExclusiveMs, 20);
        renderRides(rides);
        refreshHeatmap(key);
    }

    private void refreshHeatmap(String key) {
        long start = heatmapStart(selectedAnchorMs);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(start);
        end.add(Calendar.DAY_OF_MONTH, RideHeatmapView.WEEKS * 7 - 1);
        List<RideStatsStore.DailyStat> days = key == null
                ? java.util.Collections.emptyList()
                : store.dailyRange(key, RideStatsStore.dayKey(start), RideStatsStore.dayKey(end.getTimeInMillis()));
        heatmap.setData(selectedAnchorMs, System.currentTimeMillis(),
                RideStatsStore.dayKey(selectedAnchorMs), days);
    }

    private void renderRides(List<RideStatsStore.RideRecord> rides) {
        if (rides == null || rides.isEmpty()) {
            ridesText.setText("No rides in this period");
            return;
        }
        StringBuilder out = new StringBuilder();
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
        DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
        long now = System.currentTimeMillis();
        for (int i = 0; i < rides.size(); i++) {
            RideStatsStore.RideRecord ride = rides.get(i);
            if (i > 0) out.append("\n\n");
            if (selectedPeriod == RideStatsTracker.PERIOD_DAY) {
                out.append(time.format(new Date(ride.startedAt)));
            } else {
                out.append(date.format(new Date(ride.startedAt)))
                        .append(" · ").append(time.format(new Date(ride.startedAt)));
            }
            if (ride.isOpen()) out.append("  ·  active");
            out.append("\n")
                    .append(f("%.2f km", ride.distanceKm))
                    .append("  ·  ").append(formatDuration(ride.elapsedMs(now)))
                    .append("  ·  ").append(batteryDelta(ride));
        }
        ridesText.setText(out.toString());
    }

    private TextView periodTab(String title, int period) {
        TextView tab = text(title, 13, TEXT);
        tab.setGravity(Gravity.CENTER);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setPadding(dp(8), dp(9), dp(8), dp(9));
        tab.setOnClickListener(v -> {
            selectedPeriod = period;
            lastSummaryRefreshAt = 0L;
            refreshSummary();
        });
        return tab;
    }

    private void updatePeriodTabs() {
        styleTab(dayTab, selectedPeriod == RideStatsTracker.PERIOD_DAY);
        styleTab(weekTab, selectedPeriod == RideStatsTracker.PERIOD_WEEK);
        styleTab(monthTab, selectedPeriod == RideStatsTracker.PERIOD_MONTH);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : MUTED);
        tab.setBackground(rounded(selected ? ACCENT : Color.TRANSPARENT, 10));
    }

    private void shiftPeriod(int direction) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(selectedAnchorMs);
        if (selectedPeriod == RideStatsTracker.PERIOD_DAY) c.add(Calendar.DAY_OF_MONTH, direction);
        else if (selectedPeriod == RideStatsTracker.PERIOD_WEEK) c.add(Calendar.WEEK_OF_YEAR, direction);
        else c.add(Calendar.MONTH, direction);
        long candidate = startOfDay(c.getTimeInMillis());
        long today = startOfDay(System.currentTimeMillis());
        if (candidate > today) candidate = today;
        selectedAnchorMs = candidate;
        lastSummaryRefreshAt = 0L;
        refreshSummary();
    }

    private void updateNextArrow() {
        DateRange current = rangeFor(selectedPeriod, selectedAnchorMs);
        boolean canGoNext = current.endInclusiveMs < startOfDay(System.currentTimeMillis());
        nextPeriod.setAlpha(canGoNext ? 1f : 0.3f);
        nextPeriod.setEnabled(canGoNext);
    }

    private void choosePauseTimeout() {
        int current = repository.getRidePauseMinutes();
        String[] values = {"20 minutes", "30 minutes", "60 minutes"};
        int checked = current == 30 ? 1 : current == 60 ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Start a new ride after…")
                .setSingleChoiceItems(values, checked, (dialog, which) -> {
                    int minutes = which == 1 ? 30 : which == 2 ? 60 : 20;
                    repository.setRidePauseMinutes(minutes);
                    pauseValue.setText(minutes + " min without movement  ›");
                    dialog.dismiss();
                    Toast.makeText(this,
                            "New ride after " + minutes + " min without movement",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportStats() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "ninebot-stats-" +
                        new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json");
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void importStats() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot open destination");
                    out.write(repository.exportStatistics().getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "Statistics exported", Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT) {
                StringBuilder json = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) json.append(line).append('\n');
                }
                new AlertDialog.Builder(this)
                        .setTitle("Import statistics?")
                        .setMessage("This backup will replace local ride statistics on this phone. Scooter connection settings are not changed.")
                        .setPositiveButton("Import", (d, w) -> {
                            try {
                                repository.importStatisticsReplace(json.toString());
                                selectedAnchorMs = startOfDay(System.currentTimeMillis());
                                selectedPeriod = RideStatsTracker.PERIOD_DAY;
                                lastSummaryRefreshAt = 0L;
                                render(repository.snapshot(), true);
                                Toast.makeText(this, "Statistics imported", Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Toast.makeText(this, "Invalid statistics backup", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Statistics file error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView[] metricPair(LinearLayout root, String left, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = metricCard(row, left, true);
        TextView b = metricCard(row, right, false);
        root.addView(row, full(dp(8)));
        return new TextView[]{a, b};
    }

    private TextView metricCard(LinearLayout row, String title, boolean first) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.addView(label(title));
        TextView value = text("—", 19, TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        c.addView(value, fullTop(dp(2)));
        row.addView(c, weighted(first));
        return value;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(stroked(CARD, BORDER, 16));
        c.setElevation(dp(1));
        return c;
    }

    private TextView label(String value) {
        TextView out = text(value, 10, MUTED);
        out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        out.setLetterSpacing(0.08f);
        return out;
    }

    private TextView actionText(String value) {
        TextView out = text(value, 12, ACCENT);
        out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        out.setGravity(Gravity.CENTER);
        out.setPadding(dp(9), dp(7), dp(9), dp(7));
        out.setBackground(rounded(ACCENT_SOFT, 10));
        return out;
    }

    private TextView navArrow(String value) {
        TextView out = text(value, 28, ACCENT);
        out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        out.setGravity(Gravity.CENTER);
        return out;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private GradientDrawable stroked(int color, int strokeColor, int radiusDp) {
        GradientDrawable bg = rounded(color, radiusDp);
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private LinearLayout.LayoutParams full(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private LinearLayout.LayoutParams fullTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, top, 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams weighted(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (first) p.setMargins(0, 0, dp(4), 0);
        else p.setMargins(dp(4), 0, 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams weightedNoMargin() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.US, format, args);
    }

    private static String prettyState(ScooterConnectionState state) {
        if (state == null) return "Disconnected";
        switch (state) {
            case READY: return "Connected";
            case RECONNECTING: return "Reconnecting";
            case CONNECTING: return "Connecting";
            case SCANNING: return "Scanning";
            case CONNECTED: return "Authenticating";
            case ERROR: return "Connection error";
            default: return "Disconnected";
        }
    }

    private static String batteryDelta(RideStatsStore.RideRecord ride) {
        if (ride == null) return "battery —";
        if (ride.dischargedPercent > 0.0 && ride.chargedPercent > 0.0) {
            return f("-%.0f%% / +%.0f%%", ride.dischargedPercent, ride.chargedPercent);
        }
        if (ride.dischargedPercent > 0.0) return f("-%.0f%% battery", ride.dischargedPercent);
        if (ride.chargedPercent > 0.0) return f("+%.0f%% charged", ride.chargedPercent);
        if (ride.startBattery != null && ride.lastBattery != null) {
            int delta = ride.lastBattery - ride.startBattery;
            if (delta < 0) return delta + "% battery";
            if (delta > 0) return "+" + delta + "% charged";
        }
        return "battery —";
    }

    private static String formatDuration(long ms) {
        long minutes = Math.max(0L, ms) / 60000L;
        long hours = minutes / 60L;
        long mins = minutes % 60L;
        return hours > 0 ? String.format(Locale.US, "%dh %02dm", hours, mins)
                : String.format(Locale.US, "%dm", mins);
    }

    private static long startOfDay(long timestamp) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timestamp);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long heatmapStart(long anchorMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(anchorMs));
        int offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.add(Calendar.DAY_OF_MONTH, -offset);
        c.add(Calendar.WEEK_OF_YEAR, -(RideHeatmapView.WEEKS - 1));
        return c.getTimeInMillis();
    }

    private static DateRange rangeFor(int period, long anchorMs) {
        Calendar from = Calendar.getInstance();
        from.setTimeInMillis(startOfDay(anchorMs));
        Calendar to = (Calendar) from.clone();

        if (period == RideStatsTracker.PERIOD_WEEK) {
            int offset = (from.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            from.add(Calendar.DAY_OF_MONTH, -offset);
            to.setTimeInMillis(from.getTimeInMillis());
            to.add(Calendar.DAY_OF_MONTH, 6);
        } else if (period == RideStatsTracker.PERIOD_MONTH) {
            from.set(Calendar.DAY_OF_MONTH, 1);
            to.setTimeInMillis(from.getTimeInMillis());
            to.add(Calendar.MONTH, 1);
            to.add(Calendar.DAY_OF_MONTH, -1);
        }

        long start = from.getTimeInMillis();
        long endInclusive = to.getTimeInMillis();
        Calendar exclusive = (Calendar) to.clone();
        exclusive.add(Calendar.DAY_OF_MONTH, 1);
        return new DateRange(start, endInclusive, exclusive.getTimeInMillis(), period);
    }

    private static String formatRangeTitle(DateRange range) {
        if (range.period == RideStatsTracker.PERIOD_DAY) {
            return new SimpleDateFormat("EEE, d MMM", Locale.US).format(new Date(range.startMs));
        }
        if (range.period == RideStatsTracker.PERIOD_MONTH) {
            return new SimpleDateFormat("MMMM yyyy", Locale.US).format(new Date(range.startMs));
        }
        SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.US);
        return day.format(new Date(range.startMs)) + " – " + day.format(new Date(range.endInclusiveMs));
    }

    private static final class DateRange {
        final long startMs;
        final long endInclusiveMs;
        final long endExclusiveMs;
        final int period;

        DateRange(long startMs, long endInclusiveMs, long endExclusiveMs, int period) {
            this.startMs = startMs;
            this.endInclusiveMs = endInclusiveMs;
            this.endExclusiveMs = endExclusiveMs;
            this.period = period;
        }
    }
}
