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
import android.graphics.Color;
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

/** Clean one-favorite-scooter UI. */
public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";
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
    private TextView statusText;
    private Switch lockSwitch;
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
        root.addView(title, matchWrap(dp(26)));

        deviceText = new TextView(this);
        deviceText.setTextSize(19);
        deviceText.setTextColor(Color.rgb(35, 35, 35));
        deviceText.setGravity(Gravity.CENTER);
        root.addView(deviceText, matchWrap(dp(2)));

        addressText = new TextView(this);
        addressText.setTextSize(12);
        addressText.setTextColor(Color.GRAY);
        addressText.setGravity(Gravity.CENTER);
        root.addView(addressText, matchWrap(dp(28)));

        lockSwitch = new Switch(this);
        lockSwitch.setText("Lock status");
        lockSwitch.setTextSize(22);
        lockSwitch.setTextColor(Color.rgb(25, 25, 25));
        lockSwitch.setGravity(Gravity.CENTER_VERTICAL);
        lockSwitch.setPadding(dp(18), 0, dp(18), 0);
        lockSwitch.setEnabled(false);
        lockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitchCallback) return;
            requestDesiredState(checked);
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(82));
        switchParams.setMargins(0, 0, 0, dp(18));
        root.addView(lockSwitch, switchParams);

        selectButton = new Button(this);
        selectButton.setText("Change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> beginScan());
        root.addView(selectButton, matchWrap(dp(10)));

        shortcutButton = new Button(this);
        shortcutButton.setText("Add home shortcut");
        shortcutButton.setAllCaps(false);
        shortcutButton.setOnClickListener(v -> requestHomeShortcut());
        root.addView(shortcutButton, matchWrap(dp(24)));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Ready");
        root.addView(statusText, matchWrap(0));

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, bottomMargin);
        return p;
    }

    private void refreshBoundDevice() {
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) {
            deviceText.setText("No scooter selected");
            addressText.setText("");
            setSwitchUnknown();
            shortcutButton.setEnabled(false);
            status("Select your scooter once. It will stay the favorite.", true);
        } else {
            deviceText.setText(name == null || name.isBlank() ? "Ninebot" : name);
            addressText.setText(address);
            shortcutButton.setEnabled(true);
            if (lastLockState == null) setSwitchUnknown();
        }
    }

    private void setSwitchUnknown() {
        suppressSwitchCallback = true;
        lockSwitch.setEnabled(false);
        lockSwitch.setText("Checking lock status…");
        suppressSwitchCallback = false;
    }

    private void setSwitchState(boolean locked) {
        lastLockState = locked;
        suppressSwitchCallback = true;
        lockSwitch.setChecked(locked);
        lockSwitch.setText(locked ? "Locked" : "Unlocked");
        lockSwitch.setEnabled(true);
        suppressSwitchCallback = false;
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
        if (scanMode || scanning) return;
        String address = prefs.getString(PREF_ADDRESS, null);
        if (address == null) return;
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            setSwitchUnknown();
            status("Turn Bluetooth on.", false);
            return;
        }
        if (!connecting && (client == null || !client.isReady())) {
            connectBoundScooter(null);
        } else if (client != null && client.isReady()) {
            client.refreshLockState();
        }
    }

    private void requestDesiredState(boolean locked) {
        if (!hasBlePermissions()) {
            restoreLastSwitchState();
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            restoreLastSwitchState();
            status("Turn Bluetooth on first.", false);
            return;
        }
        if (prefs.getString(PREF_ADDRESS, null) == null) {
            restoreLastSwitchState();
            beginScan();
            return;
        }

        lockSwitch.setEnabled(false);
        status(locked ? "Locking…" : "Unlocking…", true);
        if (client != null && (connecting || client.isReady())) {
            client.setLockedWhenReady(locked);
        } else {
            connectBoundScooter(locked);
        }
    }

    private void restoreLastSwitchState() {
        if (lastLockState != null) setSwitchState(lastLockState);
        else setSwitchUnknown();
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
            setSwitchUnknown();
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override public void onStatus(String text) {
                    status(text, true);
                }

                @Override public void onReady() {
                    connecting = false;
                }

                @Override public void onLockState(boolean locked) {
                    setSwitchState(locked);
                    status(locked ? "Locked" : "Unlocked", true);
                }

                @Override public void onActionResult(boolean success, Boolean locked, String message) {
                    connecting = false;
                    if (locked != null) setSwitchState(locked);
                    else restoreLastSwitchState();
                    status(message, success);
                }

                @Override public void onDisconnected(String reason) {
                    connecting = false;
                    setSwitchUnknown();
                    status(reason, false);
                }
            });
            client.connect(device, name);
            if (desiredState != null) client.setLockedWhenReady(desiredState);
        } catch (IllegalArgumentException e) {
            connecting = false;
            setSwitchUnknown();
            status("Saved Bluetooth address is invalid. Select the scooter again.", false);
        }
    }

    private void requestHomeShortcut() {
        if (prefs.getString(PREF_ADDRESS, null) == null) {
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

    /** Scan UI only shows Ninebot/Segway candidates; other BLE devices are ignored. */
    @SuppressLint("MissingPermission")
    private void beginScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on first.", false);
            return;
        }

        scanMode = true;
        connecting = false;
        lastLockState = null;
        setSwitchUnknown();
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
                .setTitle("Select Ninebot scooter")
                .setAdapter(scanAdapter, (dialog, which) -> {
                    if (which >= 0 && which < visibleResults.size()) bind(visibleResults.get(which));
                })
                .setNegativeButton("Cancel", (dialog, which) -> finishScanAndReconnect())
                .create();
        scanDialog.setOnDismissListener(dialog -> stopScanOnly());
        scanDialog.show();
        selectButton.setEnabled(false);
        status("Searching for Ninebot scooters…", true);

        main.postDelayed(() -> {
            if (!scanMode) return;
            startActualScan();
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
        if (result == null || result.getDevice() == null || !isLikelyNinebot(result)) return;
        String address = result.getDevice().getAddress();
        scooterResults.put(address, result);
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
            visibleResults.add(result);
            visibleLabels.add(scanName(result) + "\n" + result.getDevice().getAddress());
        }
        scanAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void bind(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        BluetoothDevice device = result.getDevice();
        String name = scanName(result);
        stopScanOnly();
        scanMode = false;
        lastLockState = null;
        prefs.edit()
                .putString(PREF_ADDRESS, device.getAddress())
                .putString(PREF_NAME, name)
                .apply();
        refreshBoundDevice();
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
        status("Saved as favorite. Connecting…", true);
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
    private String scanName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            String n = record.getDeviceName();
            if (n != null && !n.isBlank()) return n;
        }
        try {
            String n = result.getDevice().getName();
            if (n != null && !n.isBlank()) return n;
        } catch (SecurityException ignored) {}
        return "Ninebot";
    }

    private boolean isLikelyNinebot(ScanResult result) {
        String name = scanName(result).toLowerCase(Locale.ROOT);
        if (name.contains("ninebot") || name.contains("nbscooter") || name.contains("segway")
                || name.contains("g30") || name.contains("max")) return true;
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getBytes() == null) return false;
        byte[] b = record.getBytes();
        for (int i = 0; i + 2 < b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0x4E && (b[i + 2] & 0xFF) == 0x42) {
                return true;
            }
        }
        return false;
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
