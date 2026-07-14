package com.thatguysservice.huami_xdrip.models.aaps;

import android.graphics.Color;

import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine;
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphBuilder;
import com.thatguysservice.huami_xdrip.UtilityModels.BgGraphCompontens;
import com.thatguysservice.huami_xdrip.models.Constants;
import com.thatguysservice.huami_xdrip.models.PersistentStore;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Holds a time-windowed history of treatments (bolus/carbs) received from
 * AndroidAPS's "xdrip" sync plugin, so it can feed both the single "latest
 * treatment" bundle fields (treatment.insulin/carbs/timeStamp) and a
 * "graph.treatment" line, the same way real xDrip+'s BgGraphBuilder does
 * (see treatmentValuesLine()/BroadcastService.java in its source). Persisted
 * the same way as AapsGraphCache, for the same reason: a Watchdrip+ restart
 * shouldn't lose history that AAPS won't resend.
 */
public class AapsTreatmentCache {
    private static final int COLOR_TREATMENT = Color.parseColor("#77aa00");

    private static final long DEDUP_WINDOW_MS = 1L * 60 * 1000;
    private static final int TREATMENTS_MAX = 100;
    private static final String PREF_KEY = "aapsTreatmentHistory";

    private static class Entry {
        double insulin = -1;
        double carbs = -1;
    }

    private static final TreeMap<Long, Entry> treatments = new TreeMap<>();
    private static boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String json = PersistentStore.getString(PREF_KEY, null);
            if (json != null) {
                Type type = new TypeToken<TreeMap<Long, Entry>>() {
                }.getType();
                TreeMap<Long, Entry> stored = new Gson().fromJson(json, type);
                if (stored != null) {
                    treatments.putAll(stored);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void persist() {
        try {
            PersistentStore.setString(PREF_KEY, new Gson().toJson(treatments));
        } catch (Exception ignored) {
        }
    }

    private static long maxAgeMs() {
        return MiBandEntry.getGraphHours() * 60L * 60 * 1000;
    }

    public static synchronized void setBolus(double amount, long atTime) {
        ensureLoaded();
        addOrMerge(atTime, amount, -1);
        persist();
    }

    public static synchronized void setCarbs(double amount, long atTime) {
        ensureLoaded();
        addOrMerge(atTime, -1, amount);
        persist();
    }

    // Merges into a nearby existing entry instead of overwriting it, so a
    // combo bolus+carbs event (reported as two separate AAPS events at
    // essentially the same instant) ends up as one point with both fields
    // set, rather than the second call clobbering the first.
    private static void addOrMerge(long timestampMs, double insulin, double carbs) {
        Long nearestKey = nearestKeyWithin(timestampMs, DEDUP_WINDOW_MS);
        Entry entry = new Entry();
        long key = timestampMs;
        if (nearestKey != null) {
            Entry existing = treatments.remove(nearestKey);
            entry.insulin = insulin > 0 ? insulin : existing.insulin;
            entry.carbs = carbs > 0 ? carbs : existing.carbs;
            key = nearestKey;
        } else {
            entry.insulin = insulin;
            entry.carbs = carbs;
        }
        treatments.put(key, entry);

        long cutoff = System.currentTimeMillis() - maxAgeMs();
        while (!treatments.isEmpty() && treatments.firstKey() < cutoff) {
            treatments.remove(treatments.firstKey());
        }
        while (treatments.size() > TREATMENTS_MAX) {
            treatments.remove(treatments.firstKey());
        }
    }

    private static Long nearestKeyWithin(long timestampMs, long toleranceMs) {
        Long floor = treatments.floorKey(timestampMs);
        Long ceiling = treatments.ceilingKey(timestampMs);
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

    public static synchronized double getInsulin() {
        ensureLoaded();
        return treatments.isEmpty() ? -1 : treatments.lastEntry().getValue().insulin;
    }

    public static synchronized double getCarbs() {
        ensureLoaded();
        return treatments.isEmpty() ? -1 : treatments.lastEntry().getValue().carbs;
    }

    public static synchronized long getTimestamp() {
        ensureLoaded();
        return treatments.isEmpty() ? -1 : treatments.lastKey();
    }

    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return treatments.isEmpty();
    }

    // Only combined doses at or above this many units get a dot at all -
    // filters out the flood of tiny SMB micro-boluses (0.05-0.3U typical)
    // that would otherwise clutter the graph with little information value.
    private static final double MIN_DISPLAY_UNITS = 0.5;

    // Doses within this window of each other are summed into one combined
    // dose before the MIN_DISPLAY_UNITS filter is applied, so e.g. three
    // 0.2U SMBs within 10 minutes register as one 0.6U dot instead of three
    // sub-threshold ones that each get dropped individually.
    private static final long COMBINE_WINDOW_MS = 10L * 60 * 1000;

    /**
     * One point per combined insulin dose (carbs-only entries are excluded
     * entirely - an unlabeled carb dot was judged not worth the clutter).
     * All qualifying dots sit at the same fixed row, matching real xDrip+'s
     * own "6 * bgScale" baseline constant (see BgGraphBuilder.java) - not
     * scaled by dose size, since amount differences aren't reliably visible
     * at watch-screen resolution anyway.
     */
    public static synchronized GraphLine buildTreatmentLine(double low, double high, boolean doMgdl) {
        ensureLoaded();
        double lowDisplay = doMgdl ? low : BgGraphBuilder.mmolConvert(low);
        double highDisplay = doMgdl ? high : BgGraphBuilder.mmolConvert(high);
        double rowHeight = doMgdl ? 6 * Constants.MMOLL_TO_MGDL : 6;
        rowHeight = Math.max(lowDisplay, Math.min(highDisplay, rowHeight));

        // Bucket doses into COMBINE_WINDOW_MS windows and sum each bucket.
        TreeMap<Long, Double> buckets = new TreeMap<>();
        for (Map.Entry<Long, Entry> e : treatments.entrySet()) {
            double insulin = e.getValue().insulin;
            if (insulin <= 0) {
                continue;
            }
            long bucketKey = (e.getKey() / COMBINE_WINDOW_MS) * COMBINE_WINDOW_MS;
            Double existing = buckets.get(bucketKey);
            buckets.put(bucketKey, existing == null ? insulin : existing + insulin);
        }

        List<GraphPoint> points = new ArrayList<>();
        for (Map.Entry<Long, Double> bucket : buckets.entrySet()) {
            if (bucket.getValue() < MIN_DISPLAY_UNITS) {
                continue;
            }
            float x = (float) (bucket.getKey() / (double) BgGraphCompontens.FUZZER);
            points.add(new GraphPoint(x, (float) rowHeight));
        }
        GraphLine line = new GraphLine();
        line.setValues(points);
        line.setColor(COLOR_TREATMENT);
        return line;
    }
}
