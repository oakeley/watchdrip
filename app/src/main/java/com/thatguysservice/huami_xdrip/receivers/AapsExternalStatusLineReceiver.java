package com.thatguysservice.huami_xdrip.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.thatguysservice.huami_xdrip.models.aaps.AapsStatusLineCache;
import com.thatguysservice.huami_xdrip.models.database.UserError;

/**
 * Consumes AndroidAPS's "com.eveningoutpost.dexdrip.ExternalStatusline"
 * broadcast (sent by its "xdrip" sync plugin when its "Send status" setting
 * is enabled) and caches the text so AapsStatusReceiver can attach it to the
 * next watch update.
 */
public class AapsExternalStatusLineReceiver extends BroadcastReceiver {
    private static final String TAG = AapsExternalStatusLineReceiver.class.getSimpleName();
    private static final String EXTRA_STATUSLINE = "com.eveningoutpost.dexdrip.Extras.Statusline";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String statusLine = intent.getStringExtra(EXTRA_STATUSLINE);
            if (statusLine == null) {
                return;
            }
            AapsStatusLineCache.set(statusLine);
            UserError.Log.d(TAG, "Received AAPS status line: " + statusLine);
        } catch (Exception e) {
            UserError.Log.e(TAG, "onReceive Error: " + e);
        }
    }
}
