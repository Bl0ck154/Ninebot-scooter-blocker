package com.bl0ck154.ninebotblocker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Clean one-favorite-scooter UI on top of the known-good v0.6.0 transport.
 * Connection/authentication state never disables the user's lock control:
 * actions can be queued while the scooter is connecting, just like v0.6.0.
 */
public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";
    private static final String PREF_LOCK_KNOWN = "lock_state_known";
    private static final String PREF_LOCK_STATE = "lock_state";
    private static final String SHORTCUT_ID = "toggle_scooter_lock";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, ScanResult> scooterResults = new LinkedHashMap<>();
    private final ArrayList<ScanResult> visibleResults = new ArrayList<>();
    private final ArrayList<String> visibleLabels = new ArrayList<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private SharedPreferences prefs;
    private TextView deviceText;
    private TextView addressText;
    private TextView stateText;
    private TextView statusText;
    private Switch lockSwitch;
    private LinearLayout toggleCard;
    private Button selectButton;
    private Button shortcutButton;
    private NinebotBleClient client;
    private AlertDialog scanDialog;
    private ArrayAdapter<String> scanAdapter;

    private boolean scanning;
    private boolean scanMode;
    private boolean connecting;
    private boolean suppressSwitchCallback;
    private Boolean lastLockState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_LOCK_KNOWN, false)) {
            lastLockState = prefs.getBoolean(PREF_LOCK_STATE, false);
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        refreshBoundDevice();
        main.post(this::autoConnectIfPossible);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(247, 247, 247));

        TextView title = new TextView(this);
        title.setText("Ninebot Quick Lock");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(28)));

        deviceText = new TextView(this);
        deviceText.setTextSize(20);
        deviceText.setTextColor(Color.rgb(30, 30, 30));
        deviceText.setGravity(Gravity.CENTER);
        root.addView(deviceText, matchWrap(dp(2)));

        addressText = new TextView(this);
        addressText.setTextSize(12);
        addressText.setTextColor(Color.GRAY);
        addressText.setGravity(Gravity.CENTER);
        root.addView(addressText, matchWrap(dp(26)));

        toggleCard = new LinearLayout(this);
        toggleCard.setOrientation(LinearLayout.HORIZONTAL);
        toggleCard.setGravity(Gravity.CENTER_VERTICAL);
        toggleCard.setPadding(dp(20), dp(14), dp(16), dp(14));
        toggleCard.setBackground(roundedCard());

        stateText = new TextView(this);
        stateText.setTextSize(22);
        stateText.setTextColor(Color.rgb(25, 25, 25));
        stateText.setText("State unknown");
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        toggleCard.addView(stateText, stateParams);

        lockSwitch = new Switch(this);
        lockSwitch.setText("");
        lockSwitch.setShowText(false);
        lockSwitch.setSwitchMinWidth(dp(66));
        applySwitchColors();
        lockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitchCallback) return;
            requestDesiredState(checked);
        });
        toggleCard.addView(lockSwitch, new LinearLayout.LayoutParams(dp(78), dp(54)));
        toggleCard.setOnClickListener(v -> {
            if (lockSwitch.isEnabled()) lockSwitch.toggle();
        });

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(86));
        cardParams.setMargins(0, 0, 0, dp(18));
        root.addView(toggleCard, cardParams);

        selectButton = new Button(this);
        selectButton.setText("Change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> beginScan());
        root.addView(selectButton, matchWrap(dp(10)));

        shortcutButton = new Button(this);
        shortcutButton.setText("Add home shortcut");
        shortcutButton.setAllCaps(false);
        shortcutButton.setOnClickListener(v -> requestHomeShortcut());
        root.addView(shortcutButton, matchWrap(dp(22)));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Ready");
        root.addView(statusText, matchWrap(0));

        setContentView(root);
    }

    private GradientDrawable roundedCard() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(220, 220, 220));
        return bg;
    }

    private void applySwitchColors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        lockSwitch.setThumbTintList(new ColorStateList(states,
                new int[]{Color.WHITE, Color.WHITE}));
        lockSwitch.setTrackTintList(new ColorStateList(states,
                new int[]{Color.rgb(55, 150, 80), Color.rgb(150, 150, 150)}));
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, bottomMargin);
        return p;
    }

    private boolean hasFavorite() {
        return prefs.getString(PREF_ADDRESS, null) != null;
    }

    private void refreshBoundDevice() {
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) {
            deviceText.setText("No scooter selected");
            addressText.setText("");
            shortcutButton.setEnabled(false);
            renderState(null);
            setControlAvailable(false);
            status("Select a scooter", true);
        } else {
            deviceText.setText(name == null || name.isBlank() ? "Ninebot" : name);
            addressText.setText(address);
            shortcutButton.setEnabled(true);
            renderState(lastLockState);
            setControlAvailable(true);
        }
    }

    private void renderState(Boolean locked) {
        suppressSwitchCallback = true;
        if (locked == null) {
            lockSwitch.setChecked(false);
            stateText.setText("State unknown");
        } else {
            lockSwitch.setChecked(locked);
            stateText.setText(locked ? "Locked" : "Unlocked");
        }
        suppressSwitchCallback = false;
    }

    private void setControlAvailable(boolean available) {
        lockSwitch.setEnabled(available);
        toggleCard.setAlpha(available ? 1f : 0.55f);
    }

    private void saveKnownState(boolean locked) {
        lastLockState = locked;
        prefs.edit()
                .putBoolean(PREF_LOCK_KNOWN, true)
                .putBoolean(PREF_LOCK_STATE, locked)
                .apply();
        renderState(locked);
        setControlAvailable(hasFavorite());
    }

    private void clearKnownState() {
        lastLockState = null;
        prefs.edit().remove(PREF_LOCK_KNOWN).remove(PREF_LOCK_STATE).apply();
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQ_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBlePermissions()) autoConnectIfPossible();
            else status("Bluetooth permission is required.", false);
        }
    }

    private void autoConnectIfPossible() {
        if (scanMode || scanning || !hasFavorite()) return;
        setControlAvailable(true);
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on", false);
            return;
        }
        if (!connecting && (client == null || !client.isReady())) {
            connectBoundScooter(null);
        } else if (client != null && client.isReady()) {
            status("Connected", true);
        }
    }

    private void requestDesiredState(boolean locked) {
        if (!hasFavorite()) {
            renderState(lastLockState);
            beginScan();
            return;
        }
        if (!hasBlePermissions()) {
            renderState(lastLockState);
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            renderState(lastLockState);
            status("Turn Bluetooth on first", false);
            return;
        }

        // Do not disable the switch while connecting. The v0.6 transport can queue
        // the requested state and execute it as soon as authentication completes.
        stateText.setText(locked ? "Locking…" : "Unlocking…");
        status(connecting ? "Command queued while connecting…" : (locked ? "Locking…" : "Unlocking…"), true);

        if (client != null && (connecting || client.isReady())) {
            client.setLockedWhenReady(locked);
        } else {
            connectBoundScooter(locked);
        }
    }

    private void connectBoundScooter(Boolean desiredState) {
        if (scanMode || scanning) return;
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) return;

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (client != null) client.closeSilently();
            connecting = true;
            setControlAvailable(true);
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override public void onStatus(String text) {
                    status(text, true);
                }

                @Override public void onReady() {
                    connecting = false;
                    setControlAvailable(true);
                    if (stateText.getText().toString().equals("State unknown")) {
                        renderState(lastLockState);
                    }
                    status("Connected", true);
                }

                @Override public void onActionResult(boolean success, Boolean locked, String message) {
                    connecting = false;
                    if (success && locked != null) {
                        saveKnownState(locked);
                    } else {
                        renderState(lastLockState);
                        setControlAvailable(true);
                    }
                    status(message, success);
                }

                @Override public void onDisconnected(String reason) {
                    connecting = false;
                    renderState(lastLockState);
                    setControlAvailable(hasFavorite());
                    status(reason, false);
                }
            });
            client.connect(device, name);
            if (desiredState != null) client.setLockedWhenReady(desiredState);
        } catch (IllegalArgumentException e) {
            connecting = false;
            renderState(lastLockState);
            setControlAvailable(hasFavorite());
            status("Saved scooter address is invalid. Select it again.", false);
        }
    }

    private void requestHomeShortcut() {
        if (!hasFavorite()) {
            Toast.makeText(this, "Select a scooter first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Home shortcuts need Android 8 or newer.", Toast.LENGTH_SHORT).show();
            return;
        }

        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Your launcher does not support pinned shortcuts.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, ToggleShortcutActivity.class)
                .setAction(ToggleShortcutActivity.ACTION_TOGGLE);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, SHORTCUT_ID)
                .setShortLabel("Scooter lock")
                .setLongLabel("Toggle scooter lock")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_lock))
                .setIntent(intent)
                .build();

        if (manager.requestPinShortcut(shortcut, null)) {
            Toast.makeText(this, "Confirm the shortcut on your home screen.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not request the home shortcut.", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void beginScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on first", false);
            return;
        }

        scanMode = true;
        connecting = false;
        if (client != null) {
            client.closeSilently();
            client = null;
        }
        stopScanOnly();

        scooterResults.clear();
        visibleResults.clear();
        visibleLabels.clear();
        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visibleLabels);
        scanDialog = new AlertDialog.Builder(this)
                .setTitle("Select scooter")
                .setAdapter(scanAdapter, (dialog, which) -> {
                    if (which >= 0 && which < visibleResults.size()) bind(visibleResults.get(which));
                })
                .setNegativeButton("Cancel", (dialog, which) -> finishScanAndReconnect())
                .create();
        scanDialog.setOnDismissListener(dialog -> stopScanOnly());
        scanDialog.show();
        selectButton.setEnabled(false);
        status("Searching…", true);

        main.postDelayed(() -> {
            if (scanMode) startActualScan();
        }, 700);
    }

    @SuppressLint("MissingPermission")
    private void startActualScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            finishScanAndReconnect();
            status("Bluetooth scanner is unavailable.", false);
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanning = true;
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            finishScanAndReconnect();
            status("Bluetooth scan failed.", false);
            return;
        }
        main.postDelayed(() -> {
            if (scanning && scanMode) {
                stopScanOnly();
                status("Scan paused. Reopen Change scooter to scan again.", true);
            }
        }, 15000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results != null) for (ScanResult r : results) handleScanResult(r);
        }

        @Override public void onScanFailed(int errorCode) {
            main.post(() -> status("Bluetooth scan failed.", false));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String name = realScanName(result);
        if (!isNinebotName(name)) return;
        scooterResults.put(result.getDevice().getAddress(), result);
        main.post(this::refreshScanList);
    }

    @SuppressLint("MissingPermission")
    private void refreshScanList() {
        if (scanAdapter == null) return;
        List<ScanResult> entries = new ArrayList<>(scooterResults.values());
        entries.sort((a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        visibleResults.clear();
        visibleLabels.clear();
        for (ScanResult result : entries) {
            String name = realScanName(result);
            if (name == null) continue;
            visibleResults.add(result);
            visibleLabels.add(name + "\n" + result.getDevice().getAddress());
        }
        scanAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void bind(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String name = realScanName(result);
        if (name == null) return;
        BluetoothDevice device = result.getDevice();
        stopScanOnly();
        scanMode = false;
        clearKnownState();
        prefs.edit()
                .putString(PREF_ADDRESS, device.getAddress())
                .putString(PREF_NAME, name)
                .apply();
        refreshBoundDevice();
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
        status("Connecting…", true);
        main.postDelayed(() -> connectBoundScooter(null), 250);
    }

    @SuppressLint("MissingPermission")
    private void stopScanOnly() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        if (selectButton != null) selectButton.setEnabled(true);
    }

    private void finishScanAndReconnect() {
        scanMode = false;
        stopScanOnly();
        main.postDelayed(this::autoConnectIfPossible, 400);
    }

    @SuppressLint("MissingPermission")
    private String realScanName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            String n = record.getDeviceName();
            if (n != null && !n.isBlank()) return n.trim();
        }
        try {
            String n = result.getDevice().getName();
            if (n != null && !n.isBlank()) return n.trim();
        } catch (SecurityException ignored) {}
        return null;
    }

    private boolean isNinebotName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("nbscooter") || n.contains("ninebot") || n.contains("segway");
    }

    private void status(String text, boolean ok) {
        main.post(() -> {
            statusText.setText(text);
            statusText.setTextColor(ok ? Color.rgb(45, 95, 60) : Color.rgb(165, 40, 40));
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null) main.postDelayed(this::autoConnectIfPossible, 150);
    }

    @Override
    protected void onDestroy() {
        scanMode = false;
        stopScanOnly();
        if (client != null) client.closeSilently();
        super.onDestroy();
    }
}
