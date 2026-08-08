package com.bl0ck154.ninebotblocker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lightweight local statistics store. All rows are scoped to a remembered scooter key. */
public final class RideStatsStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "scooter_stats.db";
    private static final int DB_VERSION = 1;

    public static final class RideRecord {
        public final long id;
        public final String scooterKey;
        public final long startedAt;
        public final Long endedAt;
        public final long lastSeenAt;
        public final double distanceKm;
        public final double dischargedPercent;
        public final double chargedPercent;
        public final double maxSpeedKmh;
        public final long connectedMs;
        public final Integer startBattery;
        public final Integer lastBattery;
        public final Double startOdometer;
        public final Double lastOdometer;
        public final boolean counted;

        RideRecord(long id, String scooterKey, long startedAt, Long endedAt, long lastSeenAt,
                   double distanceKm, double dischargedPercent, double chargedPercent,
                   double maxSpeedKmh, long connectedMs, Integer startBattery,
                   Integer lastBattery, Double startOdometer, Double lastOdometer,
                   boolean counted) {
            this.id = id;
            this.scooterKey = scooterKey;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.lastSeenAt = lastSeenAt;
            this.distanceKm = distanceKm;
            this.dischargedPercent = dischargedPercent;
            this.chargedPercent = chargedPercent;
            this.maxSpeedKmh = maxSpeedKmh;
            this.connectedMs = connectedMs;
            this.startBattery = startBattery;
            this.lastBattery = lastBattery;
            this.startOdometer = startOdometer;
            this.lastOdometer = lastOdometer;
            this.counted = counted;
        }

        public boolean isOpen() { return endedAt == null; }
        public long elapsedMs(long now) { return Math.max(0L, (endedAt == null ? now : endedAt) - startedAt); }
    }

    public static final class PeriodSummary {
        public final double distanceKm;
        public final int rides;
        public final long connectedMs;
        public final double dischargedPercent;
        public final double chargedPercent;
        public final double maxSpeedKmh;

        PeriodSummary(double distanceKm, int rides, long connectedMs, double dischargedPercent,
                      double chargedPercent, double maxSpeedKmh) {
            this.distanceKm = distanceKm;
            this.rides = rides;
            this.connectedMs = connectedMs;
            this.dischargedPercent = dischargedPercent;
            this.chargedPercent = chargedPercent;
            this.maxSpeedKmh = maxSpeedKmh;
        }
    }

    private static volatile RideStatsStore instance;

    public static RideStatsStore get(Context context) {
        if (instance == null) {
            synchronized (RideStatsStore.class) {
                if (instance == null) instance = new RideStatsStore(context.getApplicationContext());
            }
        }
        return instance;
    }

    private RideStatsStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE rides (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "scooter_key TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER," +
                "last_seen_at INTEGER NOT NULL," +
                "start_battery INTEGER," +
                "last_battery INTEGER," +
                "start_odometer REAL," +
                "last_odometer REAL," +
                "distance_km REAL NOT NULL DEFAULT 0," +
                "discharged_pct REAL NOT NULL DEFAULT 0," +
                "charged_pct REAL NOT NULL DEFAULT 0," +
                "max_speed REAL NOT NULL DEFAULT 0," +
                "connected_ms INTEGER NOT NULL DEFAULT 0," +
                "counted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_rides_scooter_time ON rides(scooter_key, started_at DESC)");
        db.execSQL("CREATE TABLE daily_stats (" +
                "scooter_key TEXT NOT NULL," +
                "day TEXT NOT NULL," +
                "distance_km REAL NOT NULL DEFAULT 0," +
                "rides INTEGER NOT NULL DEFAULT 0," +
                "connected_ms INTEGER NOT NULL DEFAULT 0," +
                "discharged_pct REAL NOT NULL DEFAULT 0," +
                "charged_pct REAL NOT NULL DEFAULT 0," +
                "max_speed REAL NOT NULL DEFAULT 0," +
                "PRIMARY KEY(scooter_key, day))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first public statistics schema.
    }

    public synchronized RideRecord openRide(String scooterKey) {
        if (scooterKey == null) return null;
        try (Cursor c = getReadableDatabase().query("rides", null,
                "scooter_key=? AND ended_at IS NULL", new String[]{scooterKey},
                null, null, "id DESC", "1")) {
            return c.moveToFirst() ? ride(c) : null;
        }
    }

    public synchronized RideRecord lastClosedRide(String scooterKey) {
        if (scooterKey == null) return null;
        try (Cursor c = getReadableDatabase().query("rides", null,
                "scooter_key=? AND ended_at IS NOT NULL", new String[]{scooterKey},
                null, null, "ended_at DESC", "1")) {
            return c.moveToFirst() ? ride(c) : null;
        }
    }

    public synchronized long startRide(String scooterKey, long now, Integer battery, Double odometer) {
        ContentValues v = new ContentValues();
        v.put("scooter_key", scooterKey);
        v.put("started_at", now);
        v.put("last_seen_at", now);
        if (battery != null) {
            v.put("start_battery", battery);
            v.put("last_battery", battery);
        }
        if (odometer != null) {
            v.put("start_odometer", odometer);
            v.put("last_odometer", odometer);
        }
        return getWritableDatabase().insertOrThrow("rides", null, v);
    }

    public synchronized RideRecord rideById(long id) {
        try (Cursor c = getReadableDatabase().query("rides", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return c.moveToFirst() ? ride(c) : null;
        }
    }

    public synchronized void applySample(long rideId, String scooterKey, long now,
                                         Integer battery, Double odometer,
                                         double distanceDeltaKm, double dischargedDelta,
                                         double chargedDelta, double speedKmh,
                                         long connectedDeltaMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            RideRecord before = rideById(rideId);
            if (before == null) return;
            double safeDistance = Math.max(0.0, distanceDeltaKm);
            double safeDischarged = Math.max(0.0, dischargedDelta);
            double safeCharged = Math.max(0.0, chargedDelta);
            long safeConnected = Math.max(0L, connectedDeltaMs);
            double maxSpeed = Math.max(before.maxSpeedKmh, Math.max(0.0, speedKmh));
            boolean countNow = !before.counted && safeDistance >= 0.005;

            ContentValues v = new ContentValues();
            v.put("last_seen_at", now);
            if (battery != null) v.put("last_battery", battery);
            if (odometer != null) v.put("last_odometer", odometer);
            v.put("distance_km", before.distanceKm + safeDistance);
            v.put("discharged_pct", before.dischargedPercent + safeDischarged);
            v.put("charged_pct", before.chargedPercent + safeCharged);
            v.put("max_speed", maxSpeed);
            v.put("connected_ms", before.connectedMs + safeConnected);
            if (countNow) v.put("counted", 1);
            db.update("rides", v, "id=?", new String[]{String.valueOf(rideId)});

            String day = dayKey(now);
            ContentValues base = new ContentValues();
            base.put("scooter_key", scooterKey);
            base.put("day", day);
            db.insertWithOnConflict("daily_stats", null, base, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE daily_stats SET distance_km=distance_km+?, rides=rides+?," +
                            " connected_ms=connected_ms+?, discharged_pct=discharged_pct+?," +
                            " charged_pct=charged_pct+?, max_speed=MAX(max_speed,?)" +
                            " WHERE scooter_key=? AND day=?",
                    new Object[]{safeDistance, countNow ? 1 : 0, safeConnected, safeDischarged,
                            safeCharged, Math.max(0.0, speedKmh), scooterKey, day});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void closeRide(long rideId, long endedAt) {
        ContentValues v = new ContentValues();
        v.put("ended_at", endedAt);
        getWritableDatabase().update("rides", v, "id=?", new String[]{String.valueOf(rideId)});
    }

    public synchronized void reopenRide(long rideId, long now) {
        ContentValues v = new ContentValues();
        v.putNull("ended_at");
        v.put("last_seen_at", now);
        getWritableDatabase().update("rides", v, "id=?", new String[]{String.valueOf(rideId)});
    }

    public synchronized void deleteRide(long rideId) {
        getWritableDatabase().delete("rides", "id=?", new String[]{String.valueOf(rideId)});
    }

    public synchronized PeriodSummary period(String scooterKey, String firstDay, String lastDay) {
        String sql = "SELECT COALESCE(SUM(distance_km),0), COALESCE(SUM(rides),0)," +
                " COALESCE(SUM(connected_ms),0), COALESCE(SUM(discharged_pct),0)," +
                " COALESCE(SUM(charged_pct),0), COALESCE(MAX(max_speed),0)" +
                " FROM daily_stats WHERE scooter_key=? AND day>=? AND day<=?";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{scooterKey, firstDay, lastDay})) {
            if (!c.moveToFirst()) return new PeriodSummary(0, 0, 0, 0, 0, 0);
            return new PeriodSummary(c.getDouble(0), c.getInt(1), c.getLong(2),
                    c.getDouble(3), c.getDouble(4), c.getDouble(5));
        }
    }

    public synchronized List<RideRecord> recentRides(String scooterKey, int limit) {
        ArrayList<RideRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("rides", null,
                "scooter_key=? AND (counted=1 OR distance_km>0)", new String[]{scooterKey},
                null, null, "started_at DESC", String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) out.add(ride(c));
        }
        return out;
    }

    public synchronized String exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", DB_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("rides", tableToJson("rides"));
        root.put("dailyStats", tableToJson("daily_stats"));
        return root.toString(2);
    }

    public synchronized void importJsonReplace(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray rides = root.getJSONArray("rides");
        JSONArray daily = root.getJSONArray("dailyStats");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("rides", null, null);
            db.delete("daily_stats", null, null);
            for (int i = 0; i < rides.length(); i++) insertJsonRow(db, "rides", rides.getJSONObject(i));
            for (int i = 0; i < daily.length(); i++) insertJsonRow(db, "daily_stats", daily.getJSONObject(i));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private JSONArray tableToJson(String table) throws JSONException {
        JSONArray array = new JSONArray();
        try (Cursor c = getReadableDatabase().query(table, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (c.isNull(i)) row.put(c.getColumnName(i), JSONObject.NULL);
                    else {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: row.put(c.getColumnName(i), c.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: row.put(c.getColumnName(i), c.getDouble(i)); break;
                            default: row.put(c.getColumnName(i), c.getString(i)); break;
                        }
                    }
                }
                array.put(row);
            }
        }
        return array;
    }

    private static void insertJsonRow(SQLiteDatabase db, String table, JSONObject row) throws JSONException {
        ContentValues v = new ContentValues();
        JSONArray names = row.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            Object value = row.get(name);
            if (value == JSONObject.NULL) v.putNull(name);
            else if (value instanceof Integer || value instanceof Long) v.put(name, ((Number) value).longValue());
            else if (value instanceof Number) v.put(name, ((Number) value).doubleValue());
            else v.put(name, String.valueOf(value));
        }
        db.insertOrThrow(table, null, v);
    }

    private static RideRecord ride(Cursor c) {
        return new RideRecord(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("scooter_key")),
                c.getLong(c.getColumnIndexOrThrow("started_at")),
                nullableLong(c, "ended_at"),
                c.getLong(c.getColumnIndexOrThrow("last_seen_at")),
                c.getDouble(c.getColumnIndexOrThrow("distance_km")),
                c.getDouble(c.getColumnIndexOrThrow("discharged_pct")),
                c.getDouble(c.getColumnIndexOrThrow("charged_pct")),
                c.getDouble(c.getColumnIndexOrThrow("max_speed")),
                c.getLong(c.getColumnIndexOrThrow("connected_ms")),
                nullableInt(c, "start_battery"), nullableInt(c, "last_battery"),
                nullableDouble(c, "start_odometer"), nullableDouble(c, "last_odometer"),
                c.getInt(c.getColumnIndexOrThrow("counted")) != 0);
    }

    private static Long nullableLong(Cursor c, String name) {
        int i = c.getColumnIndexOrThrow(name);
        return c.isNull(i) ? null : c.getLong(i);
    }

    private static Integer nullableInt(Cursor c, String name) {
        int i = c.getColumnIndexOrThrow(name);
        return c.isNull(i) ? null : c.getInt(i);
    }

    private static Double nullableDouble(Cursor c, String name) {
        int i = c.getColumnIndexOrThrow(name);
        return c.isNull(i) ? null : c.getDouble(i);
    }

    public static String dayKey(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timestamp));
    }
}