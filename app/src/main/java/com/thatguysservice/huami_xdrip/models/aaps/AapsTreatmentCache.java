package com.thatguysservice.huami_xdrip.models.aaps;

/**
 * Holds the most recent single treatment (bolus OR carbs) received from
 * AndroidAPS's "xdrip" sync plugin, so it can be attached to the next status
 * update sent to the watch. AAPS reports boluses and carbs as separate
 * events, so only whichever was more recent is kept - matching
 * DisplayData's own "show insulin if positive, else carbs" display logic.
 */
public class AapsTreatmentCache {
    private static volatile double insulin = -1;
    private static volatile double carbs = -1;
    private static volatile long timestamp = -1;

    public static synchronized void setBolus(double amount, long atTime) {
        if (atTime <= timestamp) {
            return;
        }
        insulin = amount;
        carbs = -1;
        timestamp = atTime;
    }

    public static synchronized void setCarbs(double amount, long atTime) {
        if (atTime <= timestamp) {
            return;
        }
        carbs = amount;
        insulin = -1;
        timestamp = atTime;
    }

    public static synchronized double getInsulin() {
        return insulin;
    }

    public static synchronized double getCarbs() {
        return carbs;
    }

    public static synchronized long getTimestamp() {
        return timestamp;
    }
}
