package com.thatguysservice.huami_xdrip.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.thatguysservice.huami_xdrip.models.database.UserError;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Consumes AndroidAPS's "info.nightscout.client.NEW_SGV" broadcast (sent by
 * its "xdrip" sync plugin) purely to build glucose history for the on-watch
 * graph. It does not push a watch update itself - AapsStatusReceiver (fed by
 * the Tizen plugin) drives the actual update and attaches this history.
 */
public class AapsSgvReceiver extends BroadcastReceiver {
    private static final String TAG = AapsSgvReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String sgvsJson = intent.getStringExtra("sgvs");
            if (sgvsJson == null) {
                return;
            }
            JSONArray sgvs = new JSONArray(sgvsJson);
            for (int i = 0; i < sgvs.length(); i++) {
                JSONObject entry = sgvs.getJSONObject(i);
                if (!entry.optBoolean("isValid", true)) {
                    continue;
                }
                long mills = entry.optLong("mills", -1);
                double mgdl = entry.optDouble("mgdl", -1);
                if (mills <= 0 || mgdl <= 0) {
                    continue;
                }
                AapsGraphCache.addReading(mills, mgdl);
            }
            UserError.Log.d(TAG, "Received " + sgvs.length() + " AAPS SGV entries");
        } catch (Exception e) {
            UserError.Log.e(TAG, "onReceive Error: " + e);
        }
    }
}
