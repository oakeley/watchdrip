package com.thatguysservice.huami_xdrip.services;

import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_UPDATE_BG;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_UPDATE_BG_FORCE;
import static com.thatguysservice.huami_xdrip.services.BroadcastService.INTENT_FUNCTION_KEY;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import com.thatguysservice.huami_xdrip.HuamiXdrip;
import com.thatguysservice.huami_xdrip.UtilityModels.ForegroundServiceStarter;
import com.thatguysservice.huami_xdrip.models.Constants;
import com.thatguysservice.huami_xdrip.models.Helper;
import com.thatguysservice.huami_xdrip.models.database.UserError;
import com.thatguysservice.huami_xdrip.utils.framework.WakeLockTrampoline;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;


public class GarminService extends Service {

    protected String TAG = this.getClass().getSimpleName();
    private static final int FAST_RETRY_DELAY = (int) (Constants.SECOND_IN_MS * 3);

    private ForegroundServiceStarter foregroundServiceStarter;
    private final GarminConnectIqClient connectIqClient = new GarminConnectIqClient();
    private PendingIntent retryIntent;

    // Last BG update JSON we tried to send, kept for the retry timer; cleared
    // once at least one connected device confirms it received an attempt.
    private String pendingJson = "";
    private long lastSentTime = 0;

    public static boolean shouldServiceRun() {
        return MiBandEntry.isGarminServiceEnabled();
    }

    public static void bgForce(String jsonString) {
        if (shouldServiceRun()) {
            Helper.startService(GarminService.class, INTENT_FUNCTION_KEY, CMD_UPDATE_BG, "json", jsonString);
        }
    }

    // Used after a known send failure (SDK not ready, device not connected,
    // send error) - retries sooner than the normal cadence since we have a
    // concrete reason to believe the next attempt might succeed shortly
    // (e.g. SDK finishes initializing, BLE reconnects).
    private void setFastRetryTimer() {
        if (shouldServiceRun()) {
            retryIntent = WakeLockTrampoline.getPendingIntent(this.getClass(), Constants.GARMIN_SERVICE_RETRY_ID, CMD_UPDATE_BG_FORCE);
            Helper.wakeUpIntent(HuamiXdrip.getAppContext(), FAST_RETRY_DELAY, retryIntent);
        }
    }

    private void cancelRetryTimer() {
        Helper.cancelAlarm(HuamiXdrip.getAppContext(), retryIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final PowerManager.WakeLock wl = Helper.getWakeLock("Garmin service", 60000);
        try {
            if (!shouldServiceRun()) {
                UserError.Log.d(TAG, "Garmin Service is NOT set be active - shutting down");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent != null) {
                final String function = intent.getStringExtra(INTENT_FUNCTION_KEY);
                if (function != null) {
                    handleCommand(function, intent);
                }
            }
            return START_STICKY;
        } finally {
            Helper.releaseWakeLock(wl);
        }
    }

    private void handleCommand(String function, Intent intentIn) {
        switch (function) {
            case CMD_UPDATE_BG_FORCE:
                if (pendingJson.isEmpty()) {
                    cancelRetryTimer();
                } else {
                    UserError.Log.d(TAG, "retry send last data GARMIN");
                    cancelRetryTimer();
                    updateWearBg(pendingJson);
                }
                break;
            case CMD_UPDATE_BG:
                pendingJson = intentIn.getStringExtra("json");
                cancelRetryTimer();
                updateWearBg(pendingJson);
                break;
            default:
                break;
        }
    }

    private void updateWearBg(String jsonString) {
        if (jsonString.isEmpty()) {
            cancelRetryTimer();
            return;
        }
        try {
            JSONObject bgObject = new JSONObject(jsonString).getJSONObject("bg");
            long time = bgObject.getLong("time");
            if (time == lastSentTime) {
                cancelRetryTimer();
                return;
            }

            List<Object> message = new ArrayList<>();
            message.add(jsonString);
            connectIqClient.sendToConnectedDevices(message, new GarminConnectIqClient.MessageSentListener() {
                @Override
                public void onMessageSent() {
                    lastSentTime = time;
                    pendingJson = "";
                    cancelRetryTimer();
                }

                @Override
                public void onMessageFailed(String reason) {
                    UserError.Log.d(TAG, "Send failed, will retry: " + reason);
                    cancelRetryTimer();
                    setFastRetryTimer();
                }
            });
        } catch (Exception e) {
            UserError.Log.e(TAG, "Exception: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), this);
        foregroundServiceStarter.start();
    }

    @Override
    public void onCreate() {
        UserError.Log.d(TAG, "starting service");
        startInForeground();
        connectIqClient.connect(this);
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        UserError.Log.d(TAG, "killing service");
        cancelRetryTimer();
        foregroundServiceStarter.stop();
        connectIqClient.disconnect(this);
        super.onDestroy();
    }
}
