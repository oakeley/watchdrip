package com.thatguysservice.huami_xdrip.models;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Tracks a per-minute low/in-range/high classification for the current day
 * and derives the inRange/lowRange/highRange percentages shown on the watch.
 * Not thread-safe - callers must use it from a single thread (as
 * MiBandService does, from its own handler thread).
 */
public class TimeInRangeTracker {
    private static final String TAG = TimeInRangeTracker.class.getSimpleName();
    private static final String PREF_KEY = "tirData";

    /**
     * One entry per minute-since-midnight in dayValues (index 0 is the date
     * marker, see resetDayValuesIfNewDay). The string codes are the on-disk/
     * JSON format and must not change without a migration.
     */
    private enum TirLevel {
        LOW("0"), IN_RANGE("1"), HIGH("2");

        final String code;

        TirLevel(String code) {
            this.code = code;
        }

        static TirLevel fromCode(String code) {
            for (TirLevel level : values()) {
                if (level.code.equals(code)) {
                    return level;
                }
            }
            return IN_RANGE;
        }

        static TirLevel classify(BgData bgData) {
            if (bgData.isBgHigh()) return HIGH;
            if (bgData.isBgLow()) return LOW;
            return IN_RANGE;
        }
    }

    private boolean loaded = false;
    private ArrayList<String> dayValues = new ArrayList<>();
    private long lastTimeTir = 0;
    private TirLevel lastTirLevel = TirLevel.IN_RANGE;

    private String inRange = "0";
    private String lowRange = "0";
    private String highRange = "0";

    public String getInRange() {
        return inRange;
    }

    public String getLowRange() {
        return lowRange;
    }

    public String getHighRange() {
        return highRange;
    }

    /**
     * Forces the next update() call to reload today's data from
     * PersistentStore - call this when the owning service is restarted.
     */
    public void reset() {
        loaded = false;
    }

    /**
     * Loads today's tracking data from PersistentStore, if not already
     * loaded since the last reset(). Safe to call on every update.
     */
    public void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String json = PersistentStore.getString(PREF_KEY, null);
            Type type = new TypeToken<ArrayList<String>>() {
            }.getType();
            dayValues = new Gson().fromJson(json, type);
            if (dayValues == null) {
                dayValues = new ArrayList<>();
            }
        } catch (Exception e) {
            dayValues = new ArrayList<>();
        }

        lastTirLevel = dayValues.size() > 1
                ? TirLevel.fromCode(dayValues.get(dayValues.size() - 1))
                : TirLevel.IN_RANGE;

        String todayString = new SimpleDateFormat("ddMMyyyy").format(new Date());
        resetDayValuesIfNewDay(todayString);
    }

    /**
     * Updates today's time-in-range (TIR) tracking with the latest reading and
     * persists it to PersistentStore, then recomputes inRange/lowRange/highRange.
     */
    public void update(BgData bgData) {
        if (dayValues.isEmpty()) {
            return;
        }
        try {
            Date now = new Date();
            String todayString = new SimpleDateFormat("ddMMyyyy").format(now);
            resetDayValuesIfNewDay(todayString);

            long readingTime = bgData.getTimeStamp();
            if (readingTime == lastTimeTir) {
                return; // already recorded this exact reading
            }
            lastTimeTir = readingTime;

            padMissingMinutes(now, todayString);

            lastTirLevel = TirLevel.classify(bgData);
            dayValues.add(lastTirLevel.code);

            PersistentStore.setString(PREF_KEY, new Gson().toJson(dayValues));
            calculateTirPercentages();
        } catch (Exception e) {
            UserError.Log.e(TAG, "update error: " + e);
        }
    }

    // dayValues[0] is a "ddMMyyyy" marker for the day it covers; wipe and
    // reseed it whenever that day has passed.
    private void resetDayValuesIfNewDay(String todayDateString) {
        if (dayValues.isEmpty() || !dayValues.get(0).equals(todayDateString)) {
            dayValues.clear();
            dayValues.add(todayDateString);
        }
    }

    // If minutes were missed (service wasn't running, no reading arrived),
    // backfill them with the last known level so the day's minute log has no gaps.
    private void padMissingMinutes(Date now, String todayDateString) throws ParseException {
        Date midnight = new SimpleDateFormat("ddMMyyyy").parse(todayDateString);
        long minutesSinceMidnight = (now.getTime() - midnight.getTime()) / 1000 / 60;
        while (dayValues.size() < minutesSinceMidnight) {
            dayValues.add(lastTirLevel.code);
        }
    }

    private void calculateTirPercentages() {
        int lowMinutes = 0;
        int inRangeMinutes = 0;
        int highMinutes = 0;
        for (int i = 1; i < dayValues.size(); i++) { // skip index 0, the date marker
            switch (TirLevel.fromCode(dayValues.get(i))) {
                case LOW:
                    lowMinutes++;
                    break;
                case IN_RANGE:
                    inRangeMinutes++;
                    break;
                case HIGH:
                    highMinutes++;
                    break;
            }
        }

        int totalMinutes = dayValues.size() - 1;
        int inRangePercent = minutesToPercent(inRangeMinutes, totalMinutes);
        int lowPercent = 0;
        int highPercent = 0;

        // Low/high split the remainder left after inRange. Whichever bucket has
        // more minutes gets its own rounded share; the other one absorbs
        // whatever's left, so the three percentages always add up to exactly 100.
        if (inRangePercent < 100) {
            int remainderPercent = 100 - inRangePercent;
            if (lowMinutes > 0 && highMinutes == 0) {
                lowPercent = remainderPercent;
            } else if (highMinutes > 0 && lowMinutes == 0) {
                highPercent = remainderPercent;
            } else if (lowMinutes > 0 && highMinutes > 0) {
                if (highMinutes >= lowMinutes) {
                    highPercent = roundedRangePercent(highMinutes, totalMinutes, inRangePercent);
                    lowPercent = remainderPercent - highPercent;
                } else {
                    lowPercent = roundedRangePercent(lowMinutes, totalMinutes, inRangePercent);
                    highPercent = remainderPercent - lowPercent;
                }
            }
        }

        UserError.Log.d(TAG, "TIR - in: " + inRangePercent + " low: " + lowPercent + " high: " + highPercent);

        inRange = String.valueOf(inRangePercent);
        lowRange = String.valueOf(lowPercent);
        highRange = String.valueOf(highPercent);
    }

    // Rounds up, capped at 100.
    private int minutesToPercent(int minutes, int totalMinutes) {
        if (minutes <= 0) {
            return 0;
        }
        return Math.min(100, (int) Math.ceil(((double) 100 / totalMinutes) * minutes));
    }

    // Converts a raw low/high minute count into a percentage of the day,
    // rounding down instead of up whenever rounding up would push the total over 100%.
    private int roundedRangePercent(int rangeMinutes, int totalMinutes, int inRangePercent) {
        int percent = (int) Math.round(((double) 100 / totalMinutes) * rangeMinutes);
        if (percent + inRangePercent >= 100) {
            percent = (int) Math.floor(((double) 100 / totalMinutes) * rangeMinutes);
            if (percent + inRangePercent >= 100) {
                percent = 100 - inRangePercent;
            }
        }
        return percent;
    }
}
