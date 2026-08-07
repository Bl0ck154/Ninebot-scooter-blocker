package com.bl0ck154.ninebotblocker;

/** Central live telemetry snapshot. Null means the G30 has not returned that value yet. */
public final class ScooterTelemetry {
    private Integer batteryPercent;
    private Double batteryVoltage;
    private Double batteryCurrent;
    private Double speed;
    private Double tripDistance;
    private Double totalDistance;
    private Double remainingRange;
    private Double controllerTemperature;
    private Double batteryTemperature;
    private String rideMode;
    private Boolean locked;
    private boolean connected;
    private long updatedAtMs;

    public ScooterTelemetry() {}

    private ScooterTelemetry(ScooterTelemetry other) {
        batteryPercent = other.batteryPercent;
        batteryVoltage = other.batteryVoltage;
        batteryCurrent = other.batteryCurrent;
        speed = other.speed;
        tripDistance = other.tripDistance;
        totalDistance = other.totalDistance;
        remainingRange = other.remainingRange;
        controllerTemperature = other.controllerTemperature;
        batteryTemperature = other.batteryTemperature;
        rideMode = other.rideMode;
        locked = other.locked;
        connected = other.connected;
        updatedAtMs = other.updatedAtMs;
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
    public Double getTripDistance() { return tripDistance; }
    public Double getTotalDistance() { return totalDistance; }
    public Double getRemainingRange() { return remainingRange; }
    public Double getControllerTemperature() { return controllerTemperature; }
    public Double getBatteryTemperature() { return batteryTemperature; }
    public String getRideMode() { return rideMode; }
    public Boolean getLocked() { return locked; }
    public boolean isConnected() { return connected; }
    public long getUpdatedAtMs() { return updatedAtMs; }

    void setBatteryPercent(Integer value) { batteryPercent = value; touch(); }
    void setBatteryVoltage(Double value) { batteryVoltage = value; touch(); }
    void setBatteryCurrent(Double value) { batteryCurrent = value; touch(); }
    void setSpeed(Double value) { speed = value; touch(); }
    void setTripDistance(Double value) { tripDistance = value; touch(); }
    void setTotalDistance(Double value) { totalDistance = value; touch(); }
    void setRemainingRange(Double value) { remainingRange = value; touch(); }
    void setControllerTemperature(Double value) { controllerTemperature = value; touch(); }
    void setBatteryTemperature(Double value) { batteryTemperature = value; touch(); }
    void setRideMode(String value) { rideMode = value; touch(); }
    void setLocked(Boolean value) { locked = value; touch(); }
    void setConnected(boolean value) { connected = value; touch(); }

    private void touch() { updatedAtMs = System.currentTimeMillis(); }
}
