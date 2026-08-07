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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";
    private static final int MAX_LOG_CHARS = 18000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, ScanResult> scanResults = new LinkedHashMap<>();
    private final ArrayList<ScanResult> visibleScanResults = new ArrayList<>();
    private final ArrayList<String> visibleScanLabels = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private SharedPreferences prefs;
    private TextView deviceText;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private Button lockButton;
    private Button selectButton;
    private NinebotBleClient client;
    private AlertDialog scanDialog;
    private ArrayAdapter<String> scanListAdapter;
    private boolean scanning;
    private boolean connecting;
    private boolean lockPending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        appendLog("APP", "v0.5.1 diagnostic build started; Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);
        refreshBoundDevice();
        main.post(this::autoConnectIfPossible);
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(245, 245, 245));
        page.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Ninebot Quick Lock");
        title.setTextSize(27);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(10)));

        deviceText = new TextView(this);
        deviceText.setTextSize(15);
        deviceText.setTextColor(Color.DKGRAY);
        deviceText.setGravity(Gravity.CENTER);
        root.addView(deviceText, matchWrap(dp(18)));

        lockButton = new Button(this);
        lockButton.setText("🔒  LOCK SCOOTER");
        lockButton.setTextSize(21);
        lockButton.setAllCaps(false);
        lockButton.setMinHeight(dp(76));
        lockButton.setOnClickListener(v -> lockBoundScooter());
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(86));
        lockParams.setMargins(0, 0, 0, dp(14));
        root.addView(lockButton, lockParams);

        selectButton = new Button(this);
        selectButton.setText("Select / change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> beginScan());
        root.addView(selectButton, matchWrap(dp(14)));

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Ready");
        root.addView(statusText, matchWrap(dp(16)));

        TextView logTitle = new TextView(this);
        logTitle.setText("Diagnostic log");
        logTitle.setTextSize(16);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(Color.rgb(30, 30, 30));
        root.addView(logTitle, matchWrap(dp(6)));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.rgb(232, 232, 232));
        logScroll.setFillViewport(true);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250));
        logParams.setMargins(0, 0, 0, dp(10));
        root.addView(logScroll, logParams);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(Color.rgb(30, 30, 30));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(9), dp(8), dp(9), dp(8));
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        logButtons.setGravity(Gravity.CENTER);
        root.addView(logButtons, matchWrap(0));

        Button copyLog = new Button(this);
        copyLog.setText("Copy log");
        copyLog.setAllCaps(false);
        copyLog.setOnClickListener(v -> copyLog());
        logButtons.addView(copyLog, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clearLog = new Button(this);
        clearLog.setText("Clear log");
        clearLog.setAllCaps(false);
        clearLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            logText.setText("");
            appendLog("APP", "Log cleared");
        });
        logButtons.addView(clearLog, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(page);
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
        appendLog("PERM", "Requesting BLE permissions");
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
                appendLog("PERM", "BLE permissions granted");
                Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show();
                autoConnectIfPossible();
            } else {
                status("Bluetooth permission is required.", false);
            }
        }
    }

    private void autoConnectIfPossible() {
        String saved = prefs.getString(PREF_ADDRESS, null);
        if (saved == null) {
            appendLog("AUTO", "No saved scooter; waiting for selection");
            return;
        }
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on — saved scooter will connect automatically.", false);
            return;
        }
        if (!connecting && (client == null || !client.isReady())) {
            appendLog("AUTO", "Direct-connect attempt to saved MAC " + saved + " (no scan)");
            connectBoundScooter(false);
        }
    }

    private void lockBoundScooter() {
        appendLog("UI", "LOCK button tapped");
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on first.", false);
            return;
        }
        if (prefs.getString(PREF_ADDRESS, null) == null) {
            beginScan();
            return;
        }

        lockPending = true;
        lockButton.setEnabled(false);
        if (client != null && (connecting || client.isReady())) {
            client.lockWhenReady();
        } else {
            connectBoundScooter(true);
        }
    }

    private void connectBoundScooter(boolean queueLock) {
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) return;

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (client != null) client.closeSilently();
            connecting = true;
            appendLog("CONNECT", "Creating GATT connection to " + address + " name=" + (name == null ? "?" : name));
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override public void onStatus(String text) {
                    status(text, true);
                }

                @Override public void onReady() {
                    connecting = false;
                    lockButton.setEnabled(!lockPending);
                    status("Connected. LOCK is ready.", true);
                }

                @Override public void onDisconnected(String reason) {
                    connecting = false;
                    lockPending = false;
                    lockButton.setEnabled(prefs.getString(PREF_ADDRESS, null) != null);
                    status(reason, false);
                }

                @Override public void onLockResult(boolean success, String message) {
                    connecting = false;
                    lockPending = false;
                    lockButton.setEnabled(true);
                    status(message, success);
                }
            });
            client.connect(device, name);
            if (queueLock) client.lockWhenReady();
        } catch (IllegalArgumentException e) {
            connecting = false;
            lockPending = false;
            status("Saved Bluetooth address is invalid. Select the scooter again.", false);
            prefs.edit().remove(PREF_ADDRESS).remove(PREF_NAME).apply();
            refreshBoundDevice();
        }
    }

    @SuppressLint("MissingPermission")
    private void beginScan() {
        appendLog("SCAN", "User started live scan");
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

        stopScanQuietly();
        scanResults.clear();
        visibleScanResults.clear();
        visibleScanLabels.clear();
        scanListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visibleScanLabels);

        scanDialog = new AlertDialog.Builder(this)
                .setTitle("Select scooter — live BLE scan")
                .setAdapter(scanListAdapter, (dialog, which) -> {
                    if (which >= 0 && which < visibleScanResults.size()) bind(visibleScanResults.get(which));
                })
                .setNegativeButton("Cancel", (dialog, which) -> stopScanQuietly())
                .create();
        scanDialog.setOnDismissListener(dialog -> stopScanQuietly());
        scanDialog.show();

        scanning = true;
        selectButton.setEnabled(false);
        status("Live BLE scan running — results appear immediately.", true);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            appendLog("SCAN", "startScan accepted (LOW_LATENCY, no filters)");
        } catch (Exception e) {
            scanning = false;
            selectButton.setEnabled(true);
            status("BLE scan failed to start: " + e.getMessage(), false);
            return;
        }

        main.postDelayed(() -> {
            if (scanning) {
                appendLog("SCAN", "Auto-pausing scan after 20s; user never has to wait for this timer");
                stopScanQuietly();
                status("Live scan paused. Tap Select / change scooter to scan again.", true);
            }
        }, 20000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) handleScanResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> status("BLE scan failed, code " + errorCode, false));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String address = result.getDevice().getAddress();
        boolean first = !scanResults.containsKey(address);
        scanResults.put(address, result);
        if (first) {
            String name = scanName(result);
            appendLog("SCAN+", name + " | " + address + " | RSSI " + result.getRssi()
                    + " | adv=" + scanBytes(result));
        }
        main.post(this::refreshLiveScanList);
    }

    @SuppressLint("MissingPermission")
    private void refreshLiveScanList() {
        if (scanListAdapter == null) return;
        List<ScanResult> entries = new ArrayList<>(scanResults.values());
        entries.sort((a, b) -> {
            int likely = Boolean.compare(!isLikelyNinebot(a), !isLikelyNinebot(b));
            if (likely != 0) return likely;
            return Integer.compare(b.getRssi(), a.getRssi());
        });

        visibleScanResults.clear();
        visibleScanLabels.clear();
        for (ScanResult result : entries) {
            BluetoothDevice d = result.getDevice();
            visibleScanResults.add(result);
            String prefix = isLikelyNinebot(result) ? "★ " : "";
            visibleScanLabels.add(prefix + scanName(result)
                    + "\n" + d.getAddress() + "   RSSI " + result.getRssi());
        }
        scanListAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void stopScanQuietly() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
                appendLog("SCAN", "stopScan");
            } catch (Exception e) {
                appendLog("SCAN", "stopScan exception: " + e.getMessage());
            }
        }
        scanning = false;
        if (selectButton != null) selectButton.setEnabled(true);
    }

    @SuppressLint("MissingPermission")
    private void bind(ScanResult result) {
        stopScanQuietly();
        BluetoothDevice d = result.getDevice();
        String name = scanName(result);
        prefs.edit().putString(PREF_ADDRESS, d.getAddress()).putString(PREF_NAME, name).apply();
        appendLog("BIND", "Saved " + name + " | " + d.getAddress() + " | adv=" + scanBytes(result));
        refreshBoundDevice();
        status("Saved " + name + ". Connecting directly…", true);
        connectBoundScooter(false);
    }

    @SuppressLint("MissingPermission")
    private String scanName(ScanResult result) {
        if (result != null) {
            ScanRecord record = result.getScanRecord();
            if (record != null) {
                String advertised = record.getDeviceName();
                if (advertised != null && !advertised.isBlank()) return advertised;
            }
            BluetoothDevice d = result.getDevice();
            if (d != null) {
                try {
                    String cached = d.getName();
                    if (cached != null && !cached.isBlank()) return cached;
                } catch (SecurityException ignored) {}
            }
        }
        return "(unnamed BLE)";
    }

    private boolean isLikelyNinebot(ScanResult result) {
        String name = scanName(result).toLowerCase(Locale.ROOT);
        if (name.contains("ninebot") || name.contains("segway") || name.contains("nbscooter")
                || name.contains("g30") || name.contains("max")) return true;

        ScanRecord record = result == null ? null : result.getScanRecord();
        byte[] raw = record == null ? null : record.getBytes();
        if (raw == null) return false;
        String ascii = printableAscii(raw).toLowerCase(Locale.ROOT);
        return ascii.contains("ninebot") || ascii.contains("nbscooter") || ascii.contains("segway") || ascii.contains("g30");
    }

    private String scanBytes(ScanResult result) {
        ScanRecord record = result == null ? null : result.getScanRecord();
        byte[] raw = record == null ? null : record.getBytes();
        return raw == null ? "<none>" : hex(raw);
    }

    private String printableAscii(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            int v = value & 0xFF;
            out.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        return out.toString();
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return out.toString();
    }

    private void status(String text, boolean ok) {
        statusText.setText(text);
        statusText.setTextColor(ok ? Color.rgb(40, 100, 55) : Color.rgb(170, 35, 35));
        appendLog(ok ? "STATUS" : "ERROR", text);
    }

    private void appendLog(String tag, String message) {
        main.post(() -> {
            String line = timeFormat.format(new Date()) + "  " + tag + "  " + message + "\n";
            logBuffer.append(line);
            if (logBuffer.length() > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS);
            }
            if (logText != null) logText.setText(logBuffer.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Ninebot Quick Lock diagnostic log", logBuffer.toString()));
            Toast.makeText(this, "Diagnostic log copied", Toast.LENGTH_SHORT).show();
        }
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
        stopScanQuietly();
        if (client != null) client.closeSilently();
        super.onDestroy();
    }
}
