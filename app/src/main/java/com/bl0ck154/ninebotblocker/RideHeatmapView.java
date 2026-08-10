package com.bl0ck154.ninebotblocker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact 17-week Monday-to-Sunday ride-distance heatmap. */
public final class RideHeatmapView extends View {
    public interface Listener { void onDaySelected(long timestamp); }

    public static final int WEEKS = 17;
    private static final int DAYS = 7;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Double> distanceByDay = new HashMap<>();
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMM", Locale.US);

    private Listener listener;
    private long anchorMs = System.currentTimeMillis();
    private long todayMs = System.currentTimeMillis();
    private String selectedDay = RideStatsStore.dayKey(anchorMs);
    private long startMs;
    private int cell;
    private int gap;
    private int left;
    private int top;
    private double maxDistance;

    public RideHeatmapView(Context context) {
        super(context);
        setClickable(true);
        setContentDescription("Ride activity calendar");
        text.setTextSize(dp(9));
        text.setColor(0xFF667085);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        recomputeStart();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setData(long anchorMs, long todayMs, String selectedDay,
                        List<RideStatsStore.DailyStat> days) {
        this.anchorMs = anchorMs;
        this.todayMs = todayMs;
        this.selectedDay = selectedDay;
        distanceByDay.clear();
        maxDistance = 0.0;
        if (days != null) {
            for (RideStatsStore.DailyStat day : days) {
                if (day == null || day.day == null) continue;
                distanceByDay.put(day.day, day.distanceKm);
                maxDistance = Math.max(maxDistance, day.distanceKm);
            }
        }
        recomputeStart();
        requestLayout();
        invalidate();
    }

    public long rangeStartMs() { return startMs; }

    public long rangeEndMs() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs);
        c.add(Calendar.DAY_OF_MONTH, WEEKS * DAYS - 1);
        return c.getTimeInMillis();
    }

    private void recomputeStart() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(anchorMs);
        zeroTime(c);
        int offsetFromMonday = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.add(Calendar.DAY_OF_MONTH, -offsetFromMonday);
        c.add(Calendar.WEEK_OF_YEAR, -(WEEKS - 1));
        startMs = c.getTimeInMillis();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        gap = dp(3);
        left = dp(22);
        top = dp(18);
        int usable = Math.max(dp(120), width - left - getPaddingLeft() - getPaddingRight());
        cell = Math.max(dp(8), (usable - gap * (WEEKS - 1)) / WEEKS);
        int desiredHeight = getPaddingTop() + top + DAYS * cell + (DAYS - 1) * gap + dp(8) + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int originX = getPaddingLeft() + left;
        int originY = getPaddingTop() + top;

        text.setTextSize(dp(8.5f));
        text.setColor(0xFF98A2B3);
        canvas.drawText("M", getPaddingLeft(), originY + cell * 0.8f, text);
        canvas.drawText("W", getPaddingLeft(), originY + 2 * (cell + gap) + cell * 0.8f, text);
        canvas.drawText("F", getPaddingLeft(), originY + 4 * (cell + gap) + cell * 0.8f, text);

        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(startMs);
        int previousMonth = -1;
        float lastMonthX = -1000f;
        long todayStart = startOfDay(todayMs);

        for (int week = 0; week < WEEKS; week++) {
            int x = originX + week * (cell + gap);
            Calendar weekStart = (Calendar) day.clone();
            int month = weekStart.get(Calendar.MONTH);
            if ((month != previousMonth || week == 0) && x - lastMonthX >= dp(28)) {
                text.setTextSize(dp(8.5f));
                text.setColor(0xFF667085);
                canvas.drawText(monthFormat.format(weekStart.getTime()), x, getPaddingTop() + dp(10), text);
                lastMonthX = x;
                previousMonth = month;
            }

            for (int row = 0; row < DAYS; row++) {
                long timestamp = day.getTimeInMillis();
                String key = RideStatsStore.dayKey(timestamp);
                int y = originY + row * (cell + gap);
                RectF r = new RectF(x, y, x + cell, y + cell);
                boolean future = timestamp > todayStart;
                double km = distanceByDay.containsKey(key) ? distanceByDay.get(key) : 0.0;
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(future ? 0xFFF5F7FA : heatColor(km));
                canvas.drawRoundRect(r, dp(2), dp(2), fill);

                if (key.equals(selectedDay)) {
                    stroke.setColor(0xFF101828);
                    stroke.setStrokeWidth(dp(1.5f));
                    canvas.drawRoundRect(r, dp(2), dp(2), stroke);
                } else if (timestamp == todayStart) {
                    stroke.setColor(0xFF98A2B3);
                    stroke.setStrokeWidth(dp(1f));
                    canvas.drawRoundRect(r, dp(2), dp(2), stroke);
                }
                day.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
    }

    private int heatColor(double km) {
        if (km <= 0.001) return 0xFFE8EDF2;
        double scale = maxDistance <= 0.001 ? 1.0 : km / maxDistance;
        if (scale <= 0.25) return 0xFFD5EEE9;
        if (scale <= 0.50) return 0xFF9DD9D1;
        if (scale <= 0.75) return 0xFF4FB7AA;
        return 0xFF007E79;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        int originX = getPaddingLeft() + left;
        int originY = getPaddingTop() + top;
        float relX = event.getX() - originX;
        float relY = event.getY() - originY;
        if (relX < 0 || relY < 0) return performClick();

        int step = cell + gap;
        int week = (int) (relX / step);
        int row = (int) (relY / step);
        if (week < 0 || week >= WEEKS || row < 0 || row >= DAYS) return performClick();
        if (relX - week * step > cell || relY - row * step > cell) return performClick();

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs);
        c.add(Calendar.DAY_OF_MONTH, week * DAYS + row);
        long selected = c.getTimeInMillis();
        if (selected > startOfDay(todayMs)) return performClick();
        selectedDay = RideStatsStore.dayKey(selected);
        invalidate();
        if (listener != null) listener.onDaySelected(selected);
        return performClick();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private static long startOfDay(long value) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(value);
        zeroTime(c);
        return c.getTimeInMillis();
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
