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
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, BluetoothDevice> scanDevices = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private SharedPreferences prefs;
    private TextView deviceText;
    private TextView statusText;
    private Button lockButton;
    private Button selectButton;
    private NinebotBleClient client;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        refreshBoundDevice();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        TextView title = new TextView(this);
        title.setText("Ninebot Quick Lock");
        title.setTextSize(27);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(12)));

        deviceText = new TextView(this);
        deviceText.setTextSize(15);
        deviceText.setTextColor(Color.DKGRAY);
        deviceText.setGravity(Gravity.CENTER);
        root.addView(deviceText, matchWrap(dp(24)));

        lockButton = new Button(this);
        lockButton.setText("🔒  LOCK SCOOTER");
        lockButton.setTextSize(21);
        lockButton.setAllCaps(false);
        lockButton.setMinHeight(dp(76));
        lockButton.setOnClickListener(v -> lockBoundScooter());
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(86));
        lockParams.setMargins(0, 0, 0, dp(18));
        root.addView(lockButton, lockParams);

        selectButton = new Button(this);
        selectButton.setText("Select / change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> beginScan());
        root.addView(selectButton, matchWrap(dp(22)));

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
            lockButton.setEnabled(false);
        } else {
            deviceText.setText((name == null || name.isBlank() ? "Ninebot" : name) + "\n" + address);
            lockButton.setEnabled(true);
        }
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
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBlePermissions()) {
                Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show();
            } else {
                status("Bluetooth permission is required.", false);
            }
        }
    }

    private void lockBoundScooter() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on first.", false);
            return;
        }
        String address = prefs.getString(PREF_ADDRESS, null);
        if (address == null) {
            beginScan();
            return;
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            setBusy(true);
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override
                public void onStatus(String text) {
                    status(text, true);
                }

                @Override
                public void onFinished(boolean success, String message) {
                    setBusy(false);
                    status(message, success);
                }
            });
            client.lock(device);
        } catch (IllegalArgumentException e) {
            status("Saved Bluetooth address is invalid. Select the scooter again.", false);
            prefs.edit().remove(PREF_ADDRESS).remove(PREF_NAME).apply();
            refreshBoundDevice();
        }
    }

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
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("BLE scanner is unavailable.", false);
            return;
        }

        if (scanning) stopScanAndShow();
        scanDevices.clear();
        scanning = true;
        selectButton.setEnabled(false);
        status("Scanning for nearby BLE devices for 8 seconds…", true);
        scanner.startScan(scanCallback);
        main.postDelayed(this::stopScanAndShow, 8000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String name;
            try {
                name = d.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name != null && !name.isBlank()) {
                scanDevices.put(d.getAddress(), d);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void stopScanAndShow() {
        if (!scanning) return;
        scanning = false;
        selectButton.setEnabled(true);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }

        if (scanDevices.isEmpty()) {
            status("No named BLE devices found. Keep the scooter powered on and nearby, then scan again.", false);
            return;
        }

        List<Map.Entry<String, BluetoothDevice>> entries = new ArrayList<>(scanDevices.entrySet());
        entries.sort((a, b) -> Boolean.compare(!isLikelyNinebot(a.getValue()), !isLikelyNinebot(b.getValue())));
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            BluetoothDevice d = entries.get(i).getValue();
            labels[i] = safeName(d) + "\n" + d.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select your Ninebot")
                .setItems(labels, (dialog, which) -> bind(entries.get(which).getValue()))
                .setNegativeButton("Cancel", null)
                .show();
        status("Select the scooter from the list.", true);
    }

    @SuppressLint("MissingPermission")
    private void bind(BluetoothDevice d) {
        String name = safeName(d);
        prefs.edit().putString(PREF_ADDRESS, d.getAddress()).putString(PREF_NAME, name).apply();
        refreshBoundDevice();
        status("Bound to " + name + ". Tap LOCK when needed.", true);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isBlank() ? "BLE device" : n;
        } catch (SecurityException e) {
            return "BLE device";
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isLikelyNinebot(BluetoothDevice d) {
        String n = safeName(d).toLowerCase(Locale.ROOT);
        return n.contains("ninebot") || n.contains("segway") || n.contains("nbscooter")
                || n.contains("g30") || n.contains("max");
    }

    private void setBusy(boolean busy) {
        lockButton.setEnabled(!busy && prefs.getString(PREF_ADDRESS, null) != null);
        selectButton.setEnabled(!busy && !scanning);
    }

    private void status(String text, boolean ok) {
        statusText.setText(text);
        statusText.setTextColor(ok ? Color.rgb(40, 100, 55) : Color.rgb(170, 35, 35));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (client != null) client.cancel();
        if (scanning) stopScanAndShow();
        super.onDestroy();
    }
}
