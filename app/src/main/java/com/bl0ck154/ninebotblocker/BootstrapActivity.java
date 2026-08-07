package com.bl0ck154.ninebotblocker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Small launcher used by the diagnostic build to obtain the complete set of
 * permissions Android's BLE scanner expects. We do not read GPS coordinates;
 * ACCESS_FINE_LOCATION is requested only so BLE advertisements are not filtered.
 */
public final class BootstrapActivity extends Activity {
    private static final int REQ = 1001;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestNeededPermissionsOrContinue();
    }

    private void requestNeededPermissionsOrContinue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!scan || !connect || !location) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQ);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ);
            return;
        }
        openMain();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ) {
            boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!locationGranted) {
                Toast.makeText(this,
                        "Location permission is required in this diagnostic build so Android returns unfiltered BLE scan results.",
                        Toast.LENGTH_LONG).show();
            }
            openMain();
        }
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
