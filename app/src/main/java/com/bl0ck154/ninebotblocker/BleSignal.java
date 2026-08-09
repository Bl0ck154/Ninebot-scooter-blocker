package com.bl0ck154.ninebotblocker;

/** Small shared RSSI-to-bars mapping for the dashboard and notification. */
final class BleSignal {
    private BleSignal() {}

    static int level(Integer rssi) {
        if (rssi == null) return 0;
        if (rssi >= -65) return 4;
        if (rssi >= -75) return 3;
        if (rssi >= -85) return 2;
        return 1;
    }

    static boolean weak(Integer rssi) {
        return rssi != null && rssi < -75;
    }

    static String bars(Integer rssi) {
        switch (level(rssi)) {
            case 4: return "▂▄▆█";
            case 3: return "▂▄▆";
            case 2: return "▂▄";
            case 1: return "▂";
            default: return "—";
        }
    }
}
