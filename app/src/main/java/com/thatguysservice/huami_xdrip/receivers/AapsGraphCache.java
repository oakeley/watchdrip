package com.thatguysservice.huami_xdrip.receivers;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine;
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphCompontens;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Holds the most recent glucose history received from AndroidAPS's "xdrip"
 */
class AapsGraphCache {
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

    static synchronized GraphLine buildGraphLine() {
        List<GraphPoint> points = new ArrayList<>();
        for (java.util.Map.Entry<Long, Double> entry : readings.entrySet()) {
            float x = (float) (entry.getKey() / (double) BgGraphCompontens.FUZZER);
            points.add(new GraphPoint(x, entry.getValue().floatValue()));
        }
        GraphLine line = new GraphLine();
        line.setValues(points);
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
