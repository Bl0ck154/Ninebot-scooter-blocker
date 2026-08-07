package com.bl0ck154.ninebotblocker;

/** Public connection state exposed by the repository/UI. */
public enum ScooterConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    READY,
    RECONNECTING,
    ERROR
}
