package com.bl0ck154.ninebotblocker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible home-screen shortcut that routes the toggle through ScooterService/Repository. */
public final class ToggleShortcutActivity extends Activity {
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE_LOCK";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ACTION_TOGGLE.equals(getIntent() == null ? null : getIntent().getAction())) {
            finish();
            return;
        }
        if (!ScooterRepository.get(this).hasRememberedScooter()) {
            Toast.makeText(this, "Select your Ninebot Max G30 first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, BootstrapActivity.class));
            finish();
            return;
        }
        if (!hasBlePermissions()) {
            Toast.makeText(this, "Open the app once and allow Bluetooth access.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, BootstrapActivity.class));
            finish();
            return;
        }

        Intent service = new Intent(this, ScooterService.class).setAction(ScooterService.ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "Scooter lock toggle queued", Toast.LENGTH_SHORT).show();
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
