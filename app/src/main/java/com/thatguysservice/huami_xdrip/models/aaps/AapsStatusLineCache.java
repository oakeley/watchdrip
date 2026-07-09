package com.thatguysservice.huami_xdrip.models.aaps;

/**
 * Holds the latest free-text status line received from AndroidAPS's "xdrip"
 * sync plugin (ACTION_NEW_EXTERNAL_STATUSLINE), so it can be attached to the
 * next status update sent to the watch.
 */
public class AapsStatusLineCache {
    private static volatile String statusLine = null;
    private static volatile long timestamp = 0;

    public static synchronized void set(String line) {
        statusLine = line;
        timestamp = System.currentTimeMillis();
    }

    public static synchronized String getStatusLine() {
        return statusLine;
    }

    public static synchronized long getTimestamp() {
        return timestamp;
    }
}
