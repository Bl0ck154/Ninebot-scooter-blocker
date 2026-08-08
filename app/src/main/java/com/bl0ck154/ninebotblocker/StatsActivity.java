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
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local per-scooter ride, day, week and month statistics. */
public final class StatsActivity extends Activity implements ScooterRepository.Listener {
    private static final int REQ_EXPORT = 91;
    private static final int REQ_IMPORT = 92;

    private static final int BG = Color.rgb(245, 247, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(17, 24, 39);
    private static final int MUTED = Color.rgb(102, 112, 133);
    private static final int BORDER = Color.rgb(226, 232, 240);
    private static final int ACCENT = Color.rgb(0, 126, 121);
    private static final int ACCENT_SOFT = Color.rgb(229, 247, 245);

    private ScooterRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int selectedPeriod = RideStatsTracker.PERIOD_DAY;

    private TextView modelText, connectionText, currentDistance, currentMeta,
            summaryDistance, summaryRides, summaryTime, summarySpeed,
            summaryUsed, summaryCharged, recentText;
    private Button dayButton, weekButton, monthButton, endRideButton,
            continueButton, pauseButton;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render(repository.snapshot());
            main.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        repository = ScooterRepository.get(this);
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        repository.addListener(this);
        repository.setUiActive(true);
        main.post(ticker);
    }

    @Override protected void onStop() {
        main.removeCallbacks(ticker);
        repository.removeListener(this);
        repository.setUiActive(false);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(18), top = dp(18), bottom = dp(24);
        root.setPadding(side, top, side, bottom);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(side, top + bars.top, side, bottom + bars.bottom);
                return insets;
            });
        }
        scroll.addView(root);

        TextView back = text("‹  SCOOTER", 12, ACCENT);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setPadding(0, dp(4), 0, dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back, full(0));

        TextView heading = text("Statistics", 28, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading, full(dp(2)));

        modelText = text("Ninebot / Segway Scooter", 13, MUTED);
        root.addView(modelText, full(dp(8)));

        connectionText = text("Disconnected", 12, MUTED);
        connectionText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(connectionText, full(dp(12)));

        LinearLayout currentCard = card();
        currentCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        currentCard.addView(label("CURRENT RIDE"));
        currentDistance = text("0.00 km", 38, TEXT);
        currentDistance.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentCard.addView(currentDistance);
        currentMeta = text("Waiting for telemetry", 13, MUTED);
        currentCard.addView(currentMeta);

        LinearLayout rideActions = new LinearLayout(this);
        rideActions.setOrientation(LinearLayout.HORIZONTAL);
        endRideButton = actionButton("End ride", false);
        endRideButton.setOnClickListener(v -> {
            if (repository.endCurrentRide()) Toast.makeText(this, "Ride ended", Toast.LENGTH_SHORT).show();
        });
        continueButton = actionButton("Continue previous", true);
        continueButton.setOnClickListener(v -> {
            if (repository.continuePreviousRide()) Toast.makeText(this, "Previous ride continued", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Current ride already has movement; end it first", Toast.LENGTH_LONG).show();
        });
        rideActions.addView(endRideButton, weighted(true));
        rideActions.addView(continueButton, weighted(false));
        currentCard.addView(rideActions, fullTop(dp(12)));
        root.addView(currentCard, full(dp(10)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        dayButton = periodButton("DAY", RideStatsTracker.PERIOD_DAY);
        weekButton = periodButton("WEEK", RideStatsTracker.PERIOD_WEEK);
        monthButton = periodButton("MONTH", RideStatsTracker.PERIOD_MONTH);
        tabs.addView(dayButton, weighted(true));
        tabs.addView(weekButton, weightedCenter());
        tabs.addView(monthButton, weighted(false));
        root.addView(tabs, full(dp(8)));

        LinearLayout[] first = metricPair(root, "DISTANCE", "RIDES");
        summaryDistance = (TextView) first[0].getChildAt(1);
        summaryRides = (TextView) first[1].getChildAt(1);
        LinearLayout[] second = metricPair(root, "CONNECTED", "MAX SPEED");
        summaryTime = (TextView) second[0].getChildAt(1);
        summarySpeed = (TextView) second[1].getChildAt(1);
        LinearLayout[] third = metricPair(root, "BATTERY USED", "CHARGED");
        summaryUsed = (TextView) third[0].getChildAt(1);
        summaryCharged = (TextView) third[1].getChildAt(1);

        LinearLayout recentCard = card();
        recentCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        recentCard.addView(label("RECENT RIDES"));
        recentText = text("No rides yet", 13, TEXT);
        recentText.setLineSpacing(dp(3), 1f);
        recentCard.addView(recentText, fullTop(dp(6)));
        root.addView(recentCard, full(dp(10)));

        pauseButton = secondaryButton("Ride pause timeout: 20 min  ›");
        pauseButton.setOnClickListener(v -> cyclePauseTimeout());
        root.addView(pauseButton, full(dp(8)));

        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        Button export = secondaryButton("Export JSON");
        export.setOnClickListener(v -> exportStats());
        Button importButton = secondaryButton("Import JSON");
        importButton.setOnClickListener(v -> importStats());
        backupRow.addView(export, weighted(true));
        backupRow.addView(importButton, weighted(false));
        root.addView(backupRow, full(dp(8)));

        TextView note = text("Statistics are stored locally on this phone and separated by scooter serial/MAC. Short scooter sleeps stay in the same ride until the pause timeout expires.", 11, MUTED);
        root.addView(note, full(0));

        GestureDetector gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null && e2.getX() - e1.getX() > dp(90)
                        && Math.abs(velocityX) > Math.abs(velocityY)) {
                    finish();
                    return true;
                }
                return false;
            }
        });
        scroll.setOnTouchListener((v, event) -> gesture.onTouchEvent(event));

        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets();
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(ScooterRepository.Snapshot snapshot) {
        modelText.setText(snapshot.modelName == null ? "Ninebot / Segway Scooter" : snapshot.modelName);
        boolean connected = snapshot.connectionState == ScooterConnectionState.READY && snapshot.telemetry.isConnected();
        connectionText.setText(connected ? "🟢 Connected" : "🔴 " + prettyState(snapshot.connectionState));
        connectionText.setTextColor(connected ? ACCENT : MUTED);

        RideStatsTracker.Snapshot stats = snapshot.rideStats;
        RideStatsStore.RideRecord ride = stats == null ? null : stats.currentRide;
        if (ride == null) {
            currentDistance.setText("0.00 km");
            currentMeta.setText("No active ride");
            endRideButton.setVisibility(View.GONE);
        } else {
            currentDistance.setText(f("%.2f km", ride.distanceKm));
            String battery = batteryDelta(ride);
            currentMeta.setText(formatDuration(ride.elapsedMs(System.currentTimeMillis()))
                    + "  ·  " + battery + "  ·  max " + f("%.1f km/h", ride.maxSpeedKmh));
            endRideButton.setVisibility(View.VISIBLE);
        }
        continueButton.setVisibility(stats != null && stats.canContinuePrevious ? View.VISIBLE : View.GONE);
        pauseButton.setText("Ride pause timeout: " + (stats == null ? 20 : stats.pauseMinutes) + " min  ›");

        RideStatsStore.PeriodSummary period = repository.periodStats(selectedPeriod);
        summaryDistance.setText(f("%.2f km", period.distanceKm));
        summaryRides.setText(String.valueOf(period.rides));
        summaryTime.setText(formatDuration(period.connectedMs));
        summarySpeed.setText(f("%.1f km/h", period.maxSpeedKmh));
        summaryUsed.setText(f("-%.0f%%", period.dischargedPercent));
        summaryCharged.setText(f("+%.0f%%", period.chargedPercent));

        updatePeriodButtons();
        renderRecent(repository.recentRides(8));
    }

    private void renderRecent(List<RideStatsStore.RideRecord> rides) {
        if (rides == null || rides.isEmpty()) {
            recentText.setText("No rides yet");
            return;
        }
        StringBuilder out = new StringBuilder();
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        long now = System.currentTimeMillis();
        for (int i = 0; i < rides.size(); i++) {
            RideStatsStore.RideRecord ride = rides.get(i);
            if (i > 0) out.append("\n\n");
            out.append(date.format(new Date(ride.startedAt)))
                    .append(ride.isOpen() ? "  ·  active" : "")
                    .append("\n")
                    .append(f("%.2f km", ride.distanceKm))
                    .append("  ·  ").append(formatDuration(ride.elapsedMs(now)))
                    .append("  ·  ").append(batteryDelta(ride));
        }
        recentText.setText(out.toString());
    }

    private Button periodButton(String title, int period) {
        Button b = actionButton(title, false);
        b.setOnClickListener(v -> {
            selectedPeriod = period;
            render(repository.snapshot());
        });
        return b;
    }

    private void updatePeriodButtons() {
        stylePeriod(dayButton, selectedPeriod == RideStatsTracker.PERIOD_DAY);
        stylePeriod(weekButton, selectedPeriod == RideStatsTracker.PERIOD_WEEK);
        stylePeriod(monthButton, selectedPeriod == RideStatsTracker.PERIOD_MONTH);
    }

    private void stylePeriod(Button b, boolean selected) {
        b.setTextColor(selected ? Color.WHITE : TEXT);
        b.setBackground(rounded(selected ? ACCENT : CARD, 12));
    }

    private void cyclePauseTimeout() {
        int now = repository.getRidePauseMinutes();
        int next = now == 20 ? 30 : now == 30 ? 60 : 20;
        repository.setRidePauseMinutes(next);
        Toast.makeText(this, "Short pauses up to " + next + " min stay in the same ride", Toast.LENGTH_SHORT).show();
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

    private LinearLayout[] metricPair(LinearLayout root, String left, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout a = metricCard(left);
        LinearLayout b = metricCard(right);
        row.addView(a, weighted(true));
        row.addView(b, weighted(false));
        root.addView(row, full(dp(8)));
        return new LinearLayout[]{a, b};
    }

    private LinearLayout metricCard(String title) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.addView(label(title));
        TextView value = text("—", 19, TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        c.addView(value);
        return c;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(stroked(CARD, BORDER, 16));
        c.setElevation(dp(1));
        return c;
    }

    private Button actionButton(String title, boolean accent) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(accent ? Color.WHITE : TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(9), dp(8), dp(9));
        b.setBackground(rounded(accent ? ACCENT : CARD, 12));
        return b;
    }

    private Button secondaryButton(String title) {
        Button b = actionButton(title, false);
        b.setBackground(stroked(CARD, BORDER, 12));
        return b;
    }

    private TextView label(String value) {
        TextView t = text(value, 10, MUTED);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable stroked(int color, int stroke, int radius) {
        GradientDrawable d = rounded(color, radius);
        d.setStroke(dp(1), stroke);
        return d;
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (first) p.setMargins(0, 0, dp(4), 0); else p.setMargins(dp(4), 0, 0, 0);
        return p;
    }

    private LinearLayout.LayoutParams weightedCenter() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private static String batteryDelta(RideStatsStore.RideRecord ride) {
        String used = String.format(Locale.US, "-%.0f%%", ride.dischargedPercent);
        if (ride.chargedPercent > 0.0) used += String.format(Locale.US, " / +%.0f%%", ride.chargedPercent);
        return used;
    }

    private static String formatDuration(long ms) {
        long minutes = Math.max(0L, ms) / 60000L;
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return hours > 0 ? String.format(Locale.US, "%dh %02dm", hours, rest)
                : String.format(Locale.US, "%dm", rest);
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

    private static String f(String format, Object... args) { return String.format(Locale.US, format, args); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}