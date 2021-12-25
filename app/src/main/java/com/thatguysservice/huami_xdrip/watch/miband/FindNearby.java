package com.thatguysservice.huami_xdrip.watch.miband;

// jamorham

import android.os.Bundle;

import com.thatguysservice.huami_xdrip.HuamiXdrip;
import com.thatguysservice.huami_xdrip.R;
import com.thatguysservice.huami_xdrip.models.JoH;
import com.thatguysservice.huami_xdrip.utils.bt.BtCallBack2;
import com.thatguysservice.huami_xdrip.utils.bt.ScanMeister;

public class FindNearby implements BtCallBack2 {

    private static final String TAG = "MiBand Scan";
    private static ScanMeister scanMeister;

    public synchronized void scan() {

        if (scanMeister == null) {
            scanMeister = new ScanMeister();
        } else {
            scanMeister.stop();
        }
        JoH.static_toast_long(HuamiXdrip.getAppContext().getString(R.string.miband_search_text));
        for (MiBandType b : MiBandType.values()) {
            if (!b.toString().isEmpty()) {
                scanMeister.setName(b.toString());
            }
        }
        scanMeister.addCallBack2(this, TAG).scan();
    }

    @Override
    public void btCallback2(String mac, String status, String name, Bundle bundle) {
        switch (status) {
            case ScanMeister.SCAN_FOUND_CALLBACK:
                MiBand.setMac(mac);
                MiBand.setModel(name, mac);
                JoH.static_toast_long(String.format(HuamiXdrip.getAppContext().getString(R.string.miband_search_found_text), name, mac));
                break;
            case ScanMeister.SCAN_FAILED_CALLBACK:
            case ScanMeister.SCAN_TIMEOUT_CALLBACK:
                JoH.static_toast_long(HuamiXdrip.getAppContext().getString(R.string.miband_search_failed_text));
                break;
        }
    }
}
