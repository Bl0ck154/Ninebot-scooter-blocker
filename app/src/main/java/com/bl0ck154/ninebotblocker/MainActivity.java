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

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";
    private static final String PREF_ADV = "adv";
    private static final int MAX_LOG_CHARS = 24000;

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
    private boolean scanMode;
    private boolean connecting;
    private boolean lockPending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        appendLog("APP", "v0.5.2 diagnostic build started; Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);
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
        page.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

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
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(86));
        lockParams.setMargins(0, 0, 0, dp(14));
        root.addView(lockButton, lockParams);

        selectButton = new Button(this);
        selectButton.setText("Select / change scooter");
        selectButton.setAllCaps(false);
        selectButton.setOnClickListener(v -> beginScan());
        root.addView(selectButton, matchWrap(dp(14)));

        statusText = new TextView(this);
        statusText.setTextSize(14);
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
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        logParams.setMargins(0, 0, 0, dp(10));
        root.addView(logScroll, logParams);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(Color.rgb(30, 30, 30));
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(9), dp(8), dp(9), dp(8));
        logScroll.addView(logText, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons, matchWrap(0));

        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> copyLog());
        buttons.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            logText.setText("");
            appendLog("APP", "Log cleared");
        });
        buttons.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(page);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSIONS);
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
                autoConnectIfPossible();
            } else {
                status("Bluetooth permission is required.", false);
            }
        }
    }

    private void autoConnectIfPossible() {
        if (scanMode || scanning) {
            appendLog("AUTO", "Auto-connect suppressed because scan mode is active");
            return;
        }
        String saved = prefs.getString(PREF_ADDRESS, null);
        if (saved == null) return;
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
        if (client != null && (connecting || client.isReady())) client.lockWhenReady();
        else connectBoundScooter(true);
    }

    private void connectBoundScooter(boolean queueLock) {
        if (scanMode || scanning) {
            appendLog("CONNECT", "Blocked connect request while scanning");
            return;
        }
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) return;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (client != null) client.closeSilently();
            connecting = true;
            appendLog("CONNECT", "Creating GATT connection to " + address + " name=" + (name == null ? "?" : name));
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override public void onStatus(String text) { status(text, true); }
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
            status("Saved Bluetooth address is invalid. Select the scooter again.", false);
        }
    }

    @SuppressLint("MissingPermission")
    private void beginScan() {
        appendLog("SCAN", "User requested scan");
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn Bluetooth on first.", false);
            return;
        }

        scanMode = true;
        lockPending = false;
        connecting = false;
        if (client != null) {
            appendLog("SCAN", "Closing active GATT before scan so scooter can advertise again");
            client.closeSilently();
            client = null;
        }
        stopScanOnly();

        scanResults.clear();
        visibleScanResults.clear();
        visibleScanLabels.clear();
        scanListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visibleScanLabels);
        scanDialog = new AlertDialog.Builder(this)
                .setTitle("Select scooter — disconnecting, then scanning")
                .setAdapter(scanListAdapter, (dialog, which) -> {
                    if (which >= 0 && which < visibleScanResults.size()) bind(visibleScanResults.get(which));
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    appendLog("SCAN", "Scan cancelled by user");
                    scanMode = false;
                    stopScanOnly();
                    main.postDelayed(this::autoConnectIfPossible, 400);
                })
                .create();
        scanDialog.setCanceledOnTouchOutside(false);
        scanDialog.setOnDismissListener(dialog -> stopScanOnly());
        scanDialog.show();
        selectButton.setEnabled(false);
        status("Disconnecting GATT before scan…", true);

        // Give the old GATT a moment to fully close. The G30 often does not advertise while connected.
        main.postDelayed(() -> {
            if (!scanMode) return;
            startActualScan();
        }, 700);
    }

    @SuppressLint("MissingPermission")
    private void startActualScan() {
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scanMode = false;
            selectButton.setEnabled(true);
            status("BLE scanner is unavailable.", false);
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanning = true;
            scanner.startScan(null, settings, scanCallback);
            appendLog("SCAN", "startScan accepted after GATT close (LOW_LATENCY, no filters)");
            status("Live BLE scan running — G30 should advertise now.", true);
            if (scanDialog != null) scanDialog.setTitle("Select scooter — live BLE scan");
        } catch (Exception e) {
            scanning = false;
            scanMode = false;
            selectButton.setEnabled(true);
            status("BLE scan failed to start: " + e.getMessage(), false);
            return;
        }
        main.postDelayed(() -> {
            if (scanning && scanMode) {
                appendLog("SCAN", "Auto-pausing scan after 20s");
                stopScanOnly();
                status("Live scan paused. Cancel and reopen to scan again.", true);
            }
        }, 20000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { handleScanResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results != null) for (ScanResult result : results) handleScanResult(result);
        }
        @Override public void onScanFailed(int errorCode) {
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
            String line = scanName(result) + " | " + address + " | RSSI " + result.getRssi() + " | adv=" + scanBytes(result);
            appendLog(address.equalsIgnoreCase(prefs.getString(PREF_ADDRESS, "")) ? "TARGET" : "SCAN+", line);
        }
        main.post(this::refreshLiveScanList);
    }

    @SuppressLint("MissingPermission")
    private void refreshLiveScanList() {
        if (scanListAdapter == null) return;
        List<ScanResult> entries = new ArrayList<>(scanResults.values());
        entries.sort((a, b) -> {
            int likely = Boolean.compare(!isLikelyNinebot(a), !isLikelyNinebot(b));
            return likely != 0 ? likely : Integer.compare(b.getRssi(), a.getRssi());
        });
        visibleScanResults.clear();
        visibleScanLabels.clear();
        for (ScanResult result : entries) {
            visibleScanResults.add(result);
            String prefix = isLikelyNinebot(result) ? "★ " : "";
            visibleScanLabels.add(prefix + scanName(result) + "\n" + result.getDevice().getAddress() + "   RSSI " + result.getRssi());
        }
        scanListAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void bind(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        stopScanOnly();
        scanMode = false;
        BluetoothDevice d = result.getDevice();
        String name = scanName(result);
        String adv = scanBytes(result);
        prefs.edit().putString(PREF_ADDRESS, d.getAddress()).putString(PREF_NAME, name).putString(PREF_ADV, adv).apply();
        appendLog("BIND", "Saved " + name + " / " + d.getAddress() + " adv=" + adv);
        refreshBoundDevice();
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
        main.postDelayed(() -> connectBoundScooter(false), 250);
    }

    @SuppressLint("MissingPermission")
    private void stopScanOnly() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            appendLog("SCAN", "stopScan called");
        }
        scanning = false;
        if (selectButton != null) selectButton.setEnabled(true);
    }

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
        return "(unnamed BLE)";
    }

    private String scanBytes(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getBytes() == null) return "<none>";
        return hex(record.getBytes());
    }

    private boolean isLikelyNinebot(ScanResult result) {
        String n = scanName(result).toLowerCase(Locale.ROOT);
        String adv = scanBytes(result).replace(" ", "").toUpperCase(Locale.ROOT);
        return n.contains("ninebot") || n.contains("nbscooter") || n.contains("segway") || n.contains("g30") || n.contains("max")
                || adv.contains("4E42");
    }

    private void status(String text, boolean ok) {
        main.post(() -> {
            statusText.setText(text);
            statusText.setTextColor(ok ? Color.rgb(40, 100, 55) : Color.rgb(170, 35, 35));
            appendLog("STATUS", text);
        });
    }

    private void appendLog(String tag, String text) {
        String line = timeFormat.format(new Date()) + "  " + tag + "  " + text + "\n";
        logBuffer.append(line);
        if (logBuffer.length() > MAX_LOG_CHARS) logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS);
        if (logText != null) {
            logText.setText(logBuffer.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Ninebot diagnostic log", logBuffer.toString()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private static String hex(byte[] data) {
        if (data == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null) main.postDelayed(this::autoConnectIfPossible, 150);
    }

    @Override protected void onDestroy() {
        scanMode = false;
        stopScanOnly();
        if (client != null) client.closeSilently();
        super.onDestroy();
    }
}
