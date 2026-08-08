package com.bl0ck154.ninebotblocker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Compact daily-use scooter dashboard. Bluetooth transport is owned by ScooterRepository. */
public final class MainActivity extends Activity implements ScooterRepository.Listener {
    private static final int REQ_BLE = 42;
    private static final int REQ_NOTIFICATIONS = 43;

    private static final int BG = Color.rgb(245, 247, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(17, 24, 39);
    private static final int MUTED = Color.rgb(102, 112, 133);
    private static final int BORDER = Color.rgb(226, 232, 240);
    private static final int ACCENT = Color.rgb(0, 126, 121);
    private static final int ACCENT_SOFT = Color.rgb(229, 247, 245);
    private static final int WARNING_SOFT = Color.rgb(255, 244, 229);
    private static final int WARNING_TEXT = Color.rgb(161, 92, 0);
    private static final int ERROR_SOFT = Color.rgb(253, 236, 236);
    private static final int ERROR_TEXT = Color.rgb(180, 35, 24);

    private ScooterRepository repository;
    private TextView titleText, deviceText, stateText, batteryPercentText, batteryText,
            rideText, tripText, rangeText, totalText, tempText, batteryHelpButton, chargeSoundSettings;
    private Button lockButton;
    private Switch persistentSwitch, autoConnectSwitch, chargeAlertSwitch;
    private boolean suppressSwitches;
    private boolean pendingChargeAlertPermission;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        repository = ScooterRepository.get(this);
        ScooterService.ensureNotificationChannels(this);
        buildUi();
        requestBlePermissionsIfNeeded();
    }

    @Override protected void onStart() {
        super.onStart();
        repository.addListener(this);
        if (hasBlePermissions()) repository.setUiActive(true);
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(18);
        int top = dp(18);
        int bottom = dp(20);
        root.setPadding(side, top, side, bottom);

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(side, top + bars.top, side, bottom + bars.bottom);
                return insets;
            });
        }

        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        titleText = text("Ninebot / Segway Scooter", 25, TEXT);
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(titleText, full(dp(2)));

        deviceText = text("No scooter selected", 12, MUTED);
        root.addView(deviceText, full(dp(8)));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        stateText = chip("Disconnected");
        chips.addView(stateText, wrapWithRight(0));
        root.addView(chips, full(dp(12)));

        LinearLayout hero = cardContainer();
        hero.setPadding(dp(16), dp(12), dp(16), dp(12));
        hero.addView(label("BATTERY"));
        batteryPercentText = text("--%", 46, TEXT);
        batteryPercentText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.addView(batteryPercentText);
        batteryText = text("—", 15, MUTED);
        batteryText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.addView(batteryText);
        root.addView(hero, full(dp(8)));

        TextView[] rideTrip = addMetricPair(root, "Ride", "Trip");
        rideText = rideTrip[0];
        tripText = rideTrip[1];

        TextView[] rangeTotal = addMetricPair(root, "Range", "Total");
        rangeText = rangeTotal[0];
        totalText = rangeTotal[1];

        LinearLayout tempCard = cardContainer();
        tempCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        tempCard.addView(label("TEMPERATURE"));
        tempText = text("—", 16, TEXT);
        tempText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tempCard.addView(tempText);
        root.addView(tempCard, full(dp(10)));

        lockButton = new Button(this);
        lockButton.setText("🔐  LOCK");
        lockButton.setTextSize(17);
        lockButton.setTextColor(Color.WHITE);
        lockButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lockButton.setAllCaps(false);
        lockButton.setMinHeight(0);
        lockButton.setMinimumHeight(0);
        lockButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        lockButton.setBackground(rounded(ACCENT, 14));
        lockButton.setElevation(dp(2));
        lockButton.setOnClickListener(v -> {
            Boolean locked = repository.snapshot().telemetry.getLocked();
            if (Boolean.TRUE.equals(locked)) repository.unlockScooter();
            else repository.lockScooter();
        });
        root.addView(lockButton, full(dp(10)));

        LinearLayout settingsCard = cardContainer();
        settingsCard.setPadding(dp(14), dp(2), dp(8), dp(2));

        persistentSwitch = addSetting(settingsCard, "Persistent notification");
        persistentSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitches) return;
            if (checked && !hasNotificationPermission()) {
                pendingChargeAlertPermission = false;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                setSwitchWithoutCallback(persistentSwitch, false);
                return;
            }
            setPersistentConnection(checked);
        });

        autoConnectSwitch = addSetting(settingsCard, "Auto connect");
        autoConnectSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!suppressSwitches) repository.setAutoConnectEnabled(checked);
        });

        chargeAlertSwitch = addSetting(settingsCard, "Full-charge sound alert");
        chargeAlertSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitches) return;
            if (checked && !hasNotificationPermission()) {
                pendingChargeAlertPermission = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                setSwitchWithoutCallback(chargeAlertSwitch, false);
                return;
            }
            setFullChargeAlert(checked);
        });
        root.addView(settingsCard, full(dp(6)));

        chargeSoundSettings = text("Full-charge sound settings  ›", 12, ACCENT);
        chargeSoundSettings.setGravity(Gravity.CENTER);
        chargeSoundSettings.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chargeSoundSettings.setPadding(dp(10), dp(7), dp(10), dp(7));
        chargeSoundSettings.setOnClickListener(v -> openChargeAlertSettings());
        chargeSoundSettings.setVisibility(View.GONE);
        root.addView(chargeSoundSettings, full(dp(4)));

        batteryHelpButton = text("Battery optimization help  ›", 13, ACCENT);
        batteryHelpButton.setGravity(Gravity.CENTER);
        batteryHelpButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        batteryHelpButton.setPadding(dp(10), dp(9), dp(10), dp(9));
        batteryHelpButton.setBackground(stroked(Color.TRANSPARENT, BORDER, 12));
        batteryHelpButton.setOnClickListener(v -> showBatteryHelp());
        batteryHelpButton.setVisibility(View.GONE);
        root.addView(batteryHelpButton, full(dp(8)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button selectButton = secondaryButton("Change scooter");
        selectButton.setOnClickListener(v -> startScooterPicker());
        Button shortcutButton = secondaryButton("Home shortcut");
        shortcutButton.setOnClickListener(v -> requestHomeShortcut());
        actionRow.addView(selectButton, weightedWithMargins(true));
        actionRow.addView(shortcutButton, weightedWithMargins(false));
        root.addView(actionRow, full(0));

        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets();
    }

    private TextView[] addMetricPair(LinearLayout root, String leftLabel, String rightLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView left = addMetricCard(row, leftLabel, true);
        TextView right = addMetricCard(row, rightLabel, false);
        root.addView(row, full(dp(8)));
        return new TextView[]{left, right};
    }

    private TextView addMetricCard(LinearLayout row, String title, boolean first) {
        LinearLayout card = cardContainer();
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setMinimumHeight(dp(68));
        card.addView(label(title.toUpperCase(Locale.US)));
        TextView value = text("—", 18, TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(value);
        row.addView(card, weightedWithMargins(first));
        return value;
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(stroked(CARD, BORDER, 16));
        card.setElevation(dp(1));
        return card;
    }

    private TextView label(String value) {
        TextView out = text(value, 10, MUTED);
        out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        out.setLetterSpacing(0.08f);
        return out;
    }

    private TextView chip(String value) {
        TextView out = text(value, 12, MUTED);
        out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        out.setPadding(dp(10), dp(5), dp(10), dp(5));
        out.setBackground(rounded(Color.rgb(238, 241, 245), 99));
        return out;
    }

    private Switch addSetting(LinearLayout parent, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView text = text(title, 15, TEXT);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setMinHeight(0);
        toggle.setMinimumHeight(0);
        row.addView(toggle);
        parent.addView(row, full(0));
        return toggle;
    }

    private Button secondaryButton(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(9), dp(8), dp(9));
        button.setBackground(stroked(CARD, BORDER, 12));
        return button;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        titleText.setText(snapshot.modelName == null ? "Ninebot / Segway Scooter" : snapshot.modelName);

        String name = snapshot.deviceName == null || snapshot.deviceName.trim().isEmpty()
                ? "Ninebot / Segway scooter" : snapshot.deviceName;
        deviceText.setText(snapshot.address == null ? "No scooter selected" : name + "  ·  " + snapshot.address);

        stateText.setText(prettyState(snapshot.connectionState));
        styleConnectionChip(snapshot.connectionState);

        batteryPercentText.setText(t.getBatteryPercent() == null ? "--%" : t.getBatteryPercent() + "%");
        batteryText.setText(formatBattery(t));
        rideText.setText(t.getSpeed() == null ? "—" : f("%.1f km/h", t.getSpeed()));
        tripText.setText(t.getTripDistance() == null ? "—" : f("%.2f km", t.getTripDistance()));
        rangeText.setText(t.getRemainingRange() == null ? "—" : f("%.1f km", t.getRemainingRange()));
        totalText.setText(t.getTotalDistance() == null ? "—" : f("%,.1f km", t.getTotalDistance()));
        tempText.setText(formatTemperature(t));

        Boolean locked = t.getLocked();
        boolean isLocked = Boolean.TRUE.equals(locked);
        lockButton.setText(isLocked ? "🔓  UNLOCK" : "🔐  LOCK");
        lockButton.setBackground(rounded(isLocked ? Color.rgb(31, 41, 55) : ACCENT, 14));
        lockButton.setEnabled(snapshot.address != null);
        lockButton.setAlpha(snapshot.address == null ? 0.45f : 1f);

        suppressSwitches = true;
        persistentSwitch.setChecked(snapshot.persistent);
        autoConnectSwitch.setChecked(snapshot.autoConnect);
        chargeAlertSwitch.setChecked(snapshot.fullChargeAlert);
        suppressSwitches = false;

        chargeSoundSettings.setVisibility(snapshot.fullChargeAlert ? View.VISIBLE : View.GONE);
        updateBatteryHelpVisibility(snapshot.persistent);

        if (snapshot.persistent && snapshot.connectionState == ScooterConnectionState.READY
                && snapshot.telemetry.isConnected() && hasNotificationPermission()) {
            ScooterService.startForConnectedScooter(this);
        }
    }

    private void styleConnectionChip(ScooterConnectionState state) {
        int bg = Color.rgb(238, 241, 245);
        int fg = MUTED;
        if (state == ScooterConnectionState.READY) {
            bg = ACCENT_SOFT;
            fg = ACCENT;
        } else if (state == ScooterConnectionState.RECONNECTING
                || state == ScooterConnectionState.CONNECTING
                || state == ScooterConnectionState.SCANNING
                || state == ScooterConnectionState.CONNECTED) {
            bg = WARNING_SOFT;
            fg = WARNING_TEXT;
        } else if (state == ScooterConnectionState.ERROR) {
            bg = ERROR_SOFT;
            fg = ERROR_TEXT;
        }
        stateText.setTextColor(fg);
        stateText.setBackground(rounded(bg, 99));
    }

    private String formatBattery(ScooterTelemetry t) {
        ArrayList<String> parts = new ArrayList<>();
        if (t.getBatteryVoltage() != null) parts.add(f("%.1f V", t.getBatteryVoltage()));
        if (t.getBatteryCurrent() != null) parts.add(f("%.2f A", t.getBatteryCurrent()));
        if (t.getBatteryPower() != null) parts.add(f("%.0f W", t.getBatteryPower()));
        return parts.isEmpty() ? "—" : String.join("  ·  ", parts);
    }

    private String formatTemperature(ScooterTelemetry t) {
        if (t.getControllerTemperature() != null && t.getBatteryTemperature() != null) {
            return f("Controller %.0f°C  ·  Battery %.0f°C", t.getControllerTemperature(), t.getBatteryTemperature());
        }
        if (t.getControllerTemperature() != null) return f("Controller %.0f°C", t.getControllerTemperature());
        if (t.getBatteryTemperature() != null) return f("Battery %.0f°C", t.getBatteryTemperature());
        return "—";
    }

    private void startScooterPicker() {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            return;
        }
        LinkedHashMap<String, String> namesByAddress = new LinkedHashMap<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> addresses = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select your Ninebot / Segway")
                .setAdapter(adapter, (d, which) -> {
                    if (which >= 0 && which < addresses.size()) {
                        String address = addresses.get(which);
                        repository.selectScooter(address, namesByAddress.get(address));
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> repository.stopDiscovery()).create();
        dialog.setOnDismissListener(d -> repository.stopDiscovery());
        dialog.show();

        repository.startDiscovery(new ScooterRepository.DiscoveryListener() {
            @Override public void onDevice(String address, String name, int rssi) {
                runOnUiThread(() -> {
                    if (namesByAddress.containsKey(address)) return;
                    namesByAddress.put(address, name);
                    addresses.add(address);
                    labels.add((name == null || name.trim().isEmpty() ? "Ninebot / Segway" : name)
                            + "\n" + address + "   " + rssi + " dBm");
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onFinished() {
                runOnUiThread(() -> {
                    if (labels.isEmpty()) Toast.makeText(MainActivity.this,
                            "No compatible Ninebot / Segway found. Keep the scooter switched on.",
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void requestHomeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Pinned shortcuts are not supported by this launcher.", Toast.LENGTH_LONG).show();
            return;
        }
        String model = repository.snapshot().modelName;
        Intent intent = new Intent(this, ToggleShortcutActivity.class).setAction(ToggleShortcutActivity.ACTION_TOGGLE);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "ninebot-lock-toggle")
                .setShortLabel("Scooter lock")
                .setLongLabel("Toggle " + (model == null ? "scooter" : model) + " lock")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_lock))
                .setIntent(intent)
                .build();
        manager.requestPinShortcut(info, null);
    }

    private void setPersistentConnection(boolean enabled) {
        if (enabled && !hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            setSwitchWithoutCallback(persistentSwitch, false);
            return;
        }
        if (enabled) {
            repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
            ScooterRepository.Snapshot snapshot = repository.snapshot();
            if (snapshot.connectionState == ScooterConnectionState.READY
                    && snapshot.telemetry.isConnected()) {
                ScooterService.startForConnectedScooter(this);
            }
        } else {
            if (repository.isFullChargeAlertEnabled()) repository.setFullChargeAlertEnabled(false);
            repository.setPersistentEnabled(false);
            ScooterService.stopLiveNotification(this);
        }
    }

    private void setFullChargeAlert(boolean enabled) {
        ScooterService.ensureNotificationChannels(this);
        repository.setFullChargeAlertEnabled(enabled);
        if (enabled) {
            if (!repository.isPersistentEnabled()) repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
            ScooterRepository.Snapshot snapshot = repository.snapshot();
            if (snapshot.connectionState == ScooterConnectionState.READY
                    && snapshot.telemetry.isConnected()) {
                ScooterService.startForConnectedScooter(this);
            }
            Toast.makeText(this,
                    "Full-charge alert armed. It will sound when battery rises to 100%.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openChargeAlertSettings() {
        ScooterService.ensureNotificationChannels(this);
        try {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ScooterService.CHARGE_CHANNEL_ID);
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void updateBatteryHelpVisibility(boolean backgroundNeeded) {
        if (!backgroundNeeded || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryHelpButton.setVisibility(View.GONE);
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryHelpButton.setVisibility(ignored ? View.GONE : View.VISIBLE);
    }

    private void showBatteryHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Background connection")
                .setMessage("If Android keeps stopping the scooter connection, allow this app to run reliably in the background. The app does not hold a permanent wake lock.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                })
                .setNegativeButton("Later", null).show();
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissionsIfNeeded() {
        if (hasBlePermissions()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE) {
            if (hasBlePermissions()) repository.setUiActive(true);
            else Toast.makeText(this, "Bluetooth access is required to connect to the scooter.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = hasNotificationPermission();
            if (granted) {
                if (pendingChargeAlertPermission) setFullChargeAlert(true);
                else setPersistentConnection(true);
            } else {
                Toast.makeText(this,
                        "Notification permission is required for background and charge alerts.",
                        Toast.LENGTH_LONG).show();
            }
            pendingChargeAlertPermission = false;
        }
    }

    private void setSwitchWithoutCallback(CompoundButton button, boolean checked) {
        suppressSwitches = true;
        button.setChecked(checked);
        suppressSwitches = false;
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

    private LinearLayout.LayoutParams wrapWithRight(int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, right, 0);
        return p;
    }

    private LinearLayout.LayoutParams weightedWithMargins(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (first) p.setMargins(0, 0, dp(4), 0);
        else p.setMargins(dp(4), 0, 0, 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.US, format, args);
    }
}
