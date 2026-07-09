package com.thatguysservice.huami_xdrip.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.thatguysservice.huami_xdrip.models.aaps.AapsTreatmentCache;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Consumes AndroidAPS's "info.nightscout.client.NEW_FOOD" broadcast, which
 * its "xdrip" sync plugin (somewhat confusingly) also uses to send bolus/carb
 * treatments under a "treatments" extra - separate from actual food-database
 * entries under "foods", which this receiver ignores.
 */
public class AapsTreatmentReceiver extends BroadcastReceiver {
    private static final String TAG = AapsTreatmentReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String treatmentsJson = intent.getStringExtra("treatments");
            if (treatmentsJson == null) {
                return;
            }
            JSONArray treatments = new JSONArray(treatmentsJson);
            for (int i = 0; i < treatments.length(); i++) {
                JSONObject entry = treatments.getJSONObject(i);
                if (!entry.optBoolean("isValid", true)) {
                    continue;
                }
                long date = entry.optLong("date", -1);
                if (date <= 0) {
                    continue;
                }
                if (entry.has("insulin")) {
                    AapsTreatmentCache.setBolus(entry.optDouble("insulin", -1), date);
                } else if (entry.has("carbs")) {
                    AapsTreatmentCache.setCarbs(entry.optDouble("carbs", -1), date);
                }
            }
            UserError.Log.d(TAG, "Received " + treatments.length() + " AAPS treatment entries");
        } catch (Exception e) {
            UserError.Log.e(TAG, "onReceive Error: " + e);
        }
    }
}
