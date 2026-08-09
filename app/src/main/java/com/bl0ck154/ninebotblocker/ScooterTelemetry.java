package com.bl0ck154.ninebotblocker;

/** Central live telemetry snapshot. Null means the scooter has not returned/derived that value yet. */
public final class ScooterTelemetry {
    private Integer batteryPercent;
    private Double batteryVoltage;
    private Double batteryCurrent;
    private Double speed;
    private Double totalDistance;
    private Double remainingRange;
    private Double controllerTemperature;
    private Double batteryTemperature;
    private Integer rssi;
    private Boolean charging;
    private Boolean locked;
    private boolean connected;

    public ScooterTelemetry() {}

    private ScooterTelemetry(ScooterTelemetry other) {
        batteryPercent = other.batteryPercent;
        batteryVoltage = other.batteryVoltage;
        batteryCurrent = other.batteryCurrent;
        speed = other.speed;
        totalDistance = other.totalDistance;
        remainingRange = other.remainingRange;
        controllerTemperature = other.controllerTemperature;
        batteryTemperature = other.batteryTemperature;
        rssi = other.rssi;
        charging = other.charging;
        locked = other.locked;
        connected = other.connected;
    }

    public ScooterTelemetry copy() { return new ScooterTelemetry(this); }

    public Integer getBatteryPercent() { return batteryPercent; }
    public Double getBatteryVoltage() { return batteryVoltage; }
    public Double getBatteryCurrent() { return batteryCurrent; }
    public Double getBatteryPower() {
        return batteryVoltage == null || batteryCurrent == null
                ? null : Math.abs(batteryVoltage * batteryCurrent);
    }
    public Double getSpeed() { return speed; }
    public Double getTotalDistance() { return totalDistance; }
    public Double getRemainingRange() { return remainingRange; }
    public Double getControllerTemperature() { return controllerTemperature; }
    public Double getBatteryTemperature() { return batteryTemperature; }
    public Integer getRssi() { return rssi; }
    public Boolean getCharging() { return charging; }
    public boolean isCharging() { return Boolean.TRUE.equals(charging); }
    public Boolean getLocked() { return locked; }
    public boolean isConnected() { return connected; }

    void setBatteryPercent(Integer value) { batteryPercent = value; }
    void setBatteryVoltage(Double value) { batteryVoltage = value; }
    void setBatteryCurrent(Double value) { batteryCurrent = value; }
    void setSpeed(Double value) { speed = value; }
    void setTotalDistance(Double value) { totalDistance = value; }
    void setRemainingRange(Double value) { remainingRange = value; }
    void setControllerTemperature(Double value) { controllerTemperature = value; }
    void setBatteryTemperature(Double value) { batteryTemperature = value; }
    void setRssi(Integer value) { rssi = value; }
    void setCharging(Boolean value) { charging = value; }
    void setLocked(Boolean value) { locked = value; }
    void setConnected(boolean value) { connected = value; }
}
