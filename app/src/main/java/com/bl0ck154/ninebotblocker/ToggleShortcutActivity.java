package com.bl0ck154.ninebotblocker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Invisible activity used by the pinned home-screen shortcut. */
public final class ToggleShortcutActivity extends Activity {
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE_LOCK";

    private static final String PREFS = "ninebot_quick_lock";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_NAME = "name";

    private final Handler main = new Handler(Looper.getMainLooper());
    private NinebotBleClient client;
    private boolean done;
    private boolean pairingToastShown;

    private final Runnable timeout = () -> finishWithMessage("Scooter did not respond in time.");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ACTION_TOGGLE.equals(getIntent() == null ? null : getIntent().getAction())) {
            finish();
            return;
        }
        main.post(this::startToggle);
    }

    @SuppressLint("MissingPermission")
    private void startToggle() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String address = prefs.getString(PREF_ADDRESS, null);
        String name = prefs.getString(PREF_NAME, null);
        if (address == null) {
            Toast.makeText(this, "Select a scooter in Ninebot Quick Lock first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, BootstrapActivity.class));
            finish();
            return;
        }

        if (!hasBlePermissions()) {
            Toast.makeText(this, "Open Ninebot Quick Lock once and allow Bluetooth access.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, BootstrapActivity.class));
            finish();
            return;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishWithMessage("Turn Bluetooth on first.");
            return;
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            client = new NinebotBleClient(this, new NinebotBleClient.Listener() {
                @Override public void onStatus(String status) {
                    if (!pairingToastShown && status != null && status.contains("POWER")) {
                        pairingToastShown = true;
                        Toast.makeText(ToggleShortcutActivity.this,
                                "Press the scooter POWER button once to pair.", Toast.LENGTH_LONG).show();
                    }
                }

                @Override public void onReady() {}

                @Override public void onLockState(boolean locked) {}

                @Override public void onActionResult(boolean success, Boolean locked, String message) {
                    if (done) return;
                    if (success && locked != null) {
                        finishWithMessage(locked ? "Scooter locked" : "Scooter unlocked");
                    } else {
                        finishWithMessage(message == null ? "Scooter lock toggle failed." : message);
                    }
                }

                @Override public void onDisconnected(String reason) {
                    if (!done) finishWithMessage(reason == null ? "Scooter disconnected." : reason);
                }
            });
            client.connect(device, name);
            client.toggleWhenReady();
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, 15000);
        } catch (IllegalArgumentException e) {
            finishWithMessage("Saved scooter address is invalid. Select it again in the app.");
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void finishWithMessage(String text) {
        if (done) return;
        done = true;
        main.removeCallbacks(timeout);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        if (client != null) {
            client.closeSilently();
            client = null;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(timeout);
        if (client != null) client.closeSilently();
        super.onDestroy();
    }
}
