package com.thatguysservice.huami_xdrip.receivers;

import android.graphics.Color;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine;
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphCompontens;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Holds the most recent glucose history received from AndroidAPS's "xdrip"
 */
class AapsGraphCache {
    // Default colors for the watch graph's value dots. These only apply when
    // the active watchface's config.json doesn't define its own line colors -
    // see BgGraphBuilder.applyLineSettings(), which overrides them when set.
    private static final int COLOR_LOW = Color.RED;
    private static final int COLOR_HIGH = Color.YELLOW;
    private static final int COLOR_IN_RANGE = Color.BLUE;

    // Real CGM readings are ~5 min apart, so anything closer than this must be
    // the same reading arriving from a different broadcast (Tizen vs xdrip),
    // not a genuinely new sample.
    private static final long DEDUP_WINDOW_MS = 1L * 60 * 1000;
    private static final int READINGS_PER_HOUR = 60;

    private static final TreeMap<Long, Double> readings = new TreeMap<>();

    private static long maxAgeMs() {
        return MiBandEntry.getGraphHours() * 60L * 60 * 1000;
    }

    private static int maxPoints() {
        return MiBandEntry.getGraphHours() * READINGS_PER_HOUR + 1;
    }

    /**
     * Adds a reading, or updates the nearest existing one if it falls within
     * DEDUP_WINDOW_MS, so the same underlying CGM sample reported by two
     * different broadcasts doesn't end up as two separate points.
     */
    static synchronized void addReading(long timestampMs, double mgdl) {
        Long nearestKey = nearestKeyWithin(timestampMs, DEDUP_WINDOW_MS);
        if (nearestKey != null) {
            if (nearestKey != timestampMs) {
                readings.remove(nearestKey);
                readings.put(timestampMs, mgdl);
            } else {
                readings.put(timestampMs, mgdl);
            }
        } else {
            readings.put(timestampMs, mgdl);
        }

        long cutoff = System.currentTimeMillis() - maxAgeMs();
        while (!readings.isEmpty() && readings.firstKey() < cutoff) {
            readings.remove(readings.firstKey());
        }
        int cap = maxPoints();
        while (readings.size() > cap) {
            readings.remove(readings.firstKey());
        }
    }

    private static Long nearestKeyWithin(long timestampMs, long toleranceMs) {
        Long floor = readings.floorKey(timestampMs);
        Long ceiling = readings.ceilingKey(timestampMs);
        Long best = null;
        if (floor != null && timestampMs - floor <= toleranceMs) {
            best = floor;
        }
        if (ceiling != null && ceiling - timestampMs <= toleranceMs) {
            if (best == null || (ceiling - timestampMs) < (timestampMs - best)) {
                best = ceiling;
            }
        }
        return best;
    }

    static synchronized boolean isNearExisting(long timestampMs) {
        return nearestKeyWithin(timestampMs, DEDUP_WINDOW_MS) != null;
    }

    static synchronized GraphLine buildInRangeLine(double low, double high) {
        return buildLine(low, high, COLOR_IN_RANGE);
    }

    static synchronized GraphLine buildLowLine(double low) {
        return buildLine(-Double.MAX_VALUE, low, COLOR_LOW);
    }

    static synchronized GraphLine buildHighLine(double high) {
        return buildLine(high, Double.MAX_VALUE, COLOR_HIGH);
    }

    // minInclusive/maxExclusive define the category: low is [-inf, low),
    // inRange is [low, high), high is [high, +inf) - matches the >= high
    // convention already used for the isHigh flag in AapsStatusReceiver.
    private static GraphLine buildLine(double minInclusive, double maxExclusive, int color) {
        List<GraphPoint> points = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : readings.entrySet()) {
            double mgdl = entry.getValue();
            if (mgdl < minInclusive || mgdl >= maxExclusive) {
                continue;
            }
            float x = (float) (entry.getKey() / (double) BgGraphCompontens.FUZZER);
            points.add(new GraphPoint(x, entry.getValue().floatValue()));
        }
        GraphLine line = new GraphLine();
        line.setValues(points);
        line.setColor(color);
        return line;
    }

    static synchronized boolean isEmpty() {
        return readings.isEmpty();
    }

    static synchronized long oldestTimestamp() {
        return readings.isEmpty() ? 0 : readings.firstKey();
    }

    static synchronized long newestTimestamp() {
        return readings.isEmpty() ? 0 : readings.lastKey();
    }
}
