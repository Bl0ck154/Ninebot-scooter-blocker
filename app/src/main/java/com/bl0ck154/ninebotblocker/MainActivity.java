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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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

/** Lightweight G30 dashboard. Bluetooth transport is owned by ScooterRepository. */
public final class MainActivity extends Activity implements ScooterRepository.Listener {
    private static final int REQ_BLE = 42;
    private static final int REQ_NOTIFICATIONS = 43;

    private ScooterRepository repository;
    private TextView deviceText, stateText, batteryPercentText, batteryText, rideText, tripText,
            rangeText, totalText, tempText, statusText;
    private Button lockButton, batteryHelpButton;
    private Switch persistentSwitch, autoConnectSwitch;
    private boolean suppressSwitches;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = ScooterRepository.get(this);
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
        scroll.setBackgroundColor(Color.rgb(246, 247, 249));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(34), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("Ninebot Max G30", 28, Color.rgb(20, 22, 25));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, full(dp(2)));
        deviceText = text("No scooter selected", 13, Color.GRAY);
        root.addView(deviceText, full(dp(2)));
        stateText = text("Disconnected", 16, Color.DKGRAY);
        root.addView(stateText, full(dp(20)));

        batteryPercentText = text("--%", 54, Color.rgb(25, 27, 30));
        batteryPercentText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        batteryPercentText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(batteryPercentText, full(dp(18)));

        batteryText = addCard(root, "Battery");
        rideText = addCard(root, "Ride");
        tripText = addCard(root, "Trip");
        rangeText = addCard(root, "Range");
        totalText = addCard(root, "Total");
        tempText = addCard(root, "Temperature");

        lockButton = new Button(this);
        lockButton.setText("LOCK");
        lockButton.setTextSize(20);
        lockButton.setAllCaps(false);
        lockButton.setMinHeight(dp(64));
        lockButton.setOnClickListener(v -> {
            Boolean locked = repository.snapshot().telemetry.getLocked();
            if (Boolean.TRUE.equals(locked)) repository.unlockScooter();
            else repository.lockScooter();
        });
        root.addView(lockButton, full(dp(16)));

        persistentSwitch = addSetting(root, "Persistent notification");
        persistentSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (suppressSwitches) return;
            if (checked && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                setSwitchWithoutCallback(persistentSwitch, false);
                return;
            }
            setPersistentConnection(checked);
        });

        autoConnectSwitch = addSetting(root, "Auto connect");
        autoConnectSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!suppressSwitches) repository.setAutoConnectEnabled(checked);
        });

        batteryHelpButton = new Button(this);
        batteryHelpButton.setText("Battery optimization help");
        batteryHelpButton.setAllCaps(false);
        batteryHelpButton.setOnClickListener(v -> showBatteryHelp());
        batteryHelpButton.setVisibility(View.GONE);
        root.addView(batteryHelpButton, full(dp(10)));

        Button selectButton = new Button(this);
        selectButton.setText("Select / change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> startScooterPicker());
        root.addView(selectButton, full(dp(8)));

        Button shortcutButton = new Button(this);
        shortcutButton.setText("Add home lock shortcut");
        shortcutButton.setAllCaps(false);
        shortcutButton.setOnClickListener(v -> requestHomeShortcut());
        root.addView(shortcutButton, full(dp(12)));

        statusText = text("Ready", 13, Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statusText, full(0));
        setContentView(scroll);
    }

    private TextView addCard(LinearLayout root, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(225, 227, 230));
        card.setBackground(bg);
        TextView labelView = text(label, 12, Color.GRAY);
        TextView valueView = text("—", 20, Color.rgb(25, 27, 30));
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(labelView);
        card.addView(valueView);
        root.addView(card, full(dp(10)));
        return valueView;
    }

    private Switch addSetting(LinearLayout root, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = text(label, 16, Color.rgb(30, 32, 35));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        row.addView(toggle);
        root.addView(row, full(dp(10)));
        return toggle;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) { runOnUiThread(() -> render(snapshot)); }

    private void render(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        deviceText.setText(snapshot.address == null ? "No scooter selected" : snapshot.deviceName + " · " + snapshot.address);
        stateText.setText(prettyState(snapshot.connectionState));
        statusText.setText(snapshot.status == null ? "" : snapshot.status);
        batteryPercentText.setText(t.getBatteryPercent() == null ? "--%" : t.getBatteryPercent() + "%");
        batteryText.setText(formatBattery(t));
        rideText.setText(t.getSpeed() == null ? "—" : f("%.1f km/h", t.getSpeed()));
        tripText.setText(t.getTripDistance() == null ? "—" : f("%.2f km", t.getTripDistance()));
        rangeText.setText(t.getRemainingRange() == null ? "—" : f("%.1f km", t.getRemainingRange()));
        totalText.setText(t.getTotalDistance() == null ? "—" : f("%,.1f km", t.getTotalDistance()));
        tempText.setText(formatTemperature(t));
        Boolean locked = t.getLocked();
        lockButton.setText(Boolean.TRUE.equals(locked) ? "UNLOCK" : "LOCK");
        lockButton.setEnabled(snapshot.address != null);
        suppressSwitches = true;
        persistentSwitch.setChecked(snapshot.persistent);
        autoConnectSwitch.setChecked(snapshot.autoConnect);
        suppressSwitches = false;
        updateBatteryHelpVisibility(snapshot.persistent);
    }

    private String formatBattery(ScooterTelemetry t) {
        ArrayList<String> parts = new ArrayList<>();
        if (t.getBatteryVoltage() != null) parts.add(f("%.1f V", t.getBatteryVoltage()));
        if (t.getBatteryCurrent() != null) parts.add(f("%.2f A", t.getBatteryCurrent()));
        if (t.getBatteryPower() != null) parts.add(f("%.0f W", t.getBatteryPower()));
        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    private String formatTemperature(ScooterTelemetry t) {
        if (t.getControllerTemperature() != null && t.getBatteryTemperature() != null) {
            return f("Controller %.1f°C · Battery %.1f°C", t.getControllerTemperature(), t.getBatteryTemperature());
        }
        if (t.getControllerTemperature() != null) return f("Controller %.1f°C", t.getControllerTemperature());
        if (t.getBatteryTemperature() != null) return f("Battery %.1f°C", t.getBatteryTemperature());
        return "—";
    }

    private void startScooterPicker() {
        if (!hasBlePermissions()) { requestBlePermissionsIfNeeded(); return; }
        LinkedHashMap<String, String> namesByAddress = new LinkedHashMap<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> addresses = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select your Ninebot")
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
                    labels.add((name == null || name.trim().isEmpty() ? "Ninebot" : name)
                            + "\n" + address + "   " + rssi + " dBm");
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onFinished() {
                runOnUiThread(() -> {
                    if (labels.isEmpty()) Toast.makeText(MainActivity.this,
                            "No Ninebot scooter found. Keep the G30 switched on.", Toast.LENGTH_LONG).show();
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
        Intent intent = new Intent(this, ToggleShortcutActivity.class).setAction(ToggleShortcutActivity.ACTION_TOGGLE);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "ninebot-lock-toggle")
                .setShortLabel("Ninebot lock")
                .setLongLabel("Toggle Ninebot Max lock")
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
            Intent intent = new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } else {
            repository.setPersistentEnabled(false);
            startService(new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_STOP));
        }
    }

    private void updateBatteryHelpVisibility(boolean persistent) {
        if (!persistent || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { batteryHelpButton.setVisibility(View.GONE); return; }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryHelpButton.setVisibility(ignored ? View.GONE : View.VISIBLE);
    }

    private void showBatteryHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Background connection")
                .setMessage("If Android keeps stopping the scooter connection, open Battery optimization settings and allow this app to run reliably in the background. The app does not hold a permanent wake lock.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                })
                .setNegativeButton("Later", null).show();
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
        } else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE) {
            if (hasBlePermissions()) repository.setUiActive(true);
            else Toast.makeText(this, "Bluetooth access is required to connect to the G30.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (granted) setPersistentConnection(true);
            else Toast.makeText(this, "Notification permission is needed for the persistent connection.", Toast.LENGTH_LONG).show();
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

    private LinearLayout.LayoutParams full(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String f(String format, Object... args) { return String.format(Locale.US, format, args); }
}
