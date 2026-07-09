package com.thatguysservice.huami_xdrip.models.aaps;

import android.graphics.Color;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine;
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphBuilder;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphCompontens;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Holds the most recent glucose history received from AndroidAPS's "xdrip"
 */
public class AapsGraphCache {
    // Default colors for the watch graph's value dots, matching   xDrip+' palette
    private static final int COLOR_LOW = Color.parseColor("#C30909");
    private static final int COLOR_HIGH = Color.parseColor("#FFBB33");
    private static final int COLOR_IN_RANGE = Color.parseColor("#33B5E6");

    private static final long DEDUP_WINDOW_MS = 1L * 60 * 1000;
    private static final int READINGS_MAX = 500;

    private static final TreeMap<Long, Double> readings = new TreeMap<>();

    private static long maxAgeMs() {
        return MiBandEntry.getGraphHours() * 60L * 60 * 1000;
    }

    private static int maxPoints() {
        return READINGS_MAX;
    }

    /**
     * Adds a reading, or updates the nearest existing one if it falls within
     * DEDUP_WINDOW_MS, so the same underlying CGM sample reported by two
     * different broadcasts doesn't end up as two separate points.
     */
    public static synchronized void addReading(long timestampMs, double mgdl) {
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

    public static synchronized GraphLine buildInRangeLine(double low, double high, boolean doMgdl) {
        return buildLine(low, high, COLOR_IN_RANGE, doMgdl);
    }

    public static synchronized GraphLine buildLowLine(double low, boolean doMgdl) {
        return buildLine(-Double.MAX_VALUE, low, COLOR_LOW, doMgdl);
    }

    public static synchronized GraphLine buildHighLine(double high, boolean doMgdl) {
        return buildLine(high, Double.MAX_VALUE, COLOR_HIGH, doMgdl);
    }

    // minInclusive/maxExclusive are always in mg/dl (matches how readings are
    // stored and how low/high are normalized in AapsStatusReceiver): low is
    // [-inf, low), inRange is [low, high), high is [high, +inf), matching the
    // >= high convention already used for the isHigh flag there. The output
    // GraphPoint values are converted to the display unit here, since the
    // sender (not the renderer) is expected to pre-convert them - see
    // WebServiceGraphLine, which only rounds/trims precision, not units.
    private static GraphLine buildLine(double minInclusive, double maxExclusive, int color, boolean doMgdl) {
        List<GraphPoint> points = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : readings.entrySet()) {
            double mgdl = entry.getValue();
            if (mgdl < minInclusive || mgdl >= maxExclusive) {
                continue;
            }
            float x = (float) (entry.getKey() / (double) BgGraphCompontens.FUZZER);
            float y = (float) (doMgdl ? mgdl : BgGraphBuilder.mmolConvert(mgdl));
            points.add(new GraphPoint(x, y));
        }
        GraphLine line = new GraphLine();
        line.setValues(points);
        line.setColor(color);
        return line;
    }

    // Flat threshold reference lines (2 points spanning the visible range),
    // as opposed to buildLowLine()/buildHighLine() which are the actual
    // out-of-range reading dots. Feed "graph.lowLine"/"graph.highLine",
    // rendered via BgGraphCompontens.lowLine()/highLine().
    public static synchronized GraphLine buildLowThresholdLine(double low, boolean doMgdl) {
        return buildThresholdLine(low, COLOR_LOW, doMgdl);
    }

    public static synchronized GraphLine buildHighThresholdLine(double high, boolean doMgdl) {
        return buildThresholdLine(high, COLOR_HIGH, doMgdl);
    }

    // value is always in mg/dl (see buildLine()) and converted here to match
    // the display unit, same reasoning as buildLine().
    private static GraphLine buildThresholdLine(double value, int color, boolean doMgdl) {
        List<GraphPoint> points = new ArrayList<>();
        if (!readings.isEmpty()) {
            float startX = (float) (readings.firstKey() / (double) BgGraphCompontens.FUZZER);
            float endX = (float) (readings.lastKey() / (double) BgGraphCompontens.FUZZER);
            float y = (float) (doMgdl ? value : BgGraphBuilder.mmolConvert(value));
            points.add(new GraphPoint(startX, y));
            points.add(new GraphPoint(endX, y));
        }
        GraphLine line = new GraphLine();
        line.setValues(points);
        line.setColor(color);
        return line;
    }

    public static synchronized boolean isEmpty() {
        return readings.isEmpty();
    }

    public static synchronized long oldestTimestamp() {
        return readings.isEmpty() ? 0 : readings.firstKey();
    }

    public static synchronized long newestTimestamp() {
        return readings.isEmpty() ? 0 : readings.lastKey();
    }
}
