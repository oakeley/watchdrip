package com.thatguysservice.huami_xdrip.models;

/**
 * Pref-backed record of when the Garmin watch last confirmed receiving a BG
 * push, via an HTTP ping to this app's local WebServer (see MiBandService's
 * /garmin_ack CGI endpoint - the watch calls it right after successfully
 * processing a push, over the same Garmin-Connect-proxied-localhost channel
 * GarminSugar already uses to pull data). Read by SettingsFragment to show a
 * live status line under the "Enable Garmin Service" toggle.
 *
 * "Connected" is computed live from freshness, the same way the watch's own
 * isDataStale() decides whether to show red/white text - not a separate
 * state machine - so there's nothing to keep in sync and no risk of it
 * getting stuck on a stale value.
 */
public class GarminConnectionStatus {

    // Deliberately NOT "miband"-prefixed: SettingsFragment's existing
    // prefListener treats any "miband"-prefixed key (other than
    // PREF_MIBAND_ENABLED) as a reason to call MiBandEntry.refresh(), which
    // restarts MiBandService - not something a Garmin status update should
    // trigger. SettingsFragment matches on this prefix explicitly instead.
    public static final String PREF_PREFIX = "garmin_status_";
    private static final String PREF_LAST_ACK_TIME = PREF_PREFIX + "last_ack_time";

    // Matches the watch's own STALE_THRESHOLD_SECONDS (WatchdripSyncView.mc).
    private static final long STALE_THRESHOLD_MS = 10 * 60 * 1000;

    public static void recordAck() {
        Pref.setLong(PREF_LAST_ACK_TIME, System.currentTimeMillis());
    }

    public static void clear() {
        Pref.setLong(PREF_LAST_ACK_TIME, 0);
    }

    public static boolean isConnected() {
        long last = getLastAckTimeMs();
        return last > 0 && (System.currentTimeMillis() - last) < STALE_THRESHOLD_MS;
    }

    public static long getLastAckTimeMs() {
        return Pref.getLong(PREF_LAST_ACK_TIME, 0);
    }
}
