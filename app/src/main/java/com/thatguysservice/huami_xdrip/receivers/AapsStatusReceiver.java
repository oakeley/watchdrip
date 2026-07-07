package com.thatguysservice.huami_xdrip.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.thatguysservice.huami_xdrip.models.database.UserError;
import com.thatguysservice.huami_xdrip.watch.miband.MiBandEntry;

import static com.thatguysservice.huami_xdrip.services.BroadcastService.CMD_UPDATE_BG_FORCE;

/**
 * Consumes AndroidAPS's "info.nightscout.androidaps.status" broadcast (sent by
 * its Tizen sync plugin) and feeds the reading into the same BG pipeline used
 * for xDrip+, so it can be relayed to the watch.
 */
public class AapsStatusReceiver extends BroadcastReceiver {
    private static final String TAG = AapsStatusReceiver.class.getSimpleName();
    private static final long STALE_THRESHOLD_MS = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null || !extras.containsKey("glucoseMgdl")) {
                return;
            }
            double valueMgdl = extras.getDouble("glucoseMgdl", -1000);
            if (valueMgdl <= 0) {
                return;
            }
            long timeStamp = extras.getLong("glucoseTimeStamp", -1);
            double deltaMgdl = extras.getDouble("deltaMgdl", 0);
            String slopeArrow = extras.getString("slopeArrow");
            double high = extras.getDouble("high", Double.MAX_VALUE);
            double low = extras.getDouble("low", -Double.MAX_VALUE);
            boolean doMgdl = !"mmol".equalsIgnoreCase(extras.getString("units", "mg/dl"));

            Bundle bgBundle = new Bundle();
            bgBundle.putDouble("bg.valueMgdl", valueMgdl);
            bgBundle.putDouble("bg.deltaValueMgdl", deltaMgdl);
            bgBundle.putLong("bg.timeStamp", timeStamp);
            bgBundle.putBoolean("bg.isStale", timeStamp > 0 && (System.currentTimeMillis() - timeStamp) > STALE_THRESHOLD_MS);
            bgBundle.putBoolean("doMgdl", doMgdl);
            bgBundle.putString("bg.deltaName", slopeArrow);
            bgBundle.putBoolean("bg.isHigh", valueMgdl >= high);
            bgBundle.putBoolean("bg.isLow", valueMgdl <= low);

            UserError.Log.d(TAG, "Received AAPS status broadcast, BG: " + valueMgdl);
            MiBandEntry.sendToService(CMD_UPDATE_BG_FORCE, bgBundle);
        } catch (Exception e) {
            UserError.Log.e(TAG, "onReceive Error: " + e);
        }
    }
}
