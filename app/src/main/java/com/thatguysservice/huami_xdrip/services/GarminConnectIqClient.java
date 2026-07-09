package com.thatguysservice.huami_xdrip.services;

import android.app.Service;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import java.util.List;

/**
 * Thin wrapper around the Garmin ConnectIQ SDK: connects/disconnects the SDK
 * and sends a message to every connected device that has our companion app
 * installed. Not thread-safe beyond what the ConnectIQ SDK itself guarantees.
 */
public class GarminConnectIqClient {
    private static final String TAG = GarminConnectIqClient.class.getSimpleName();
    private static final String APP_ID = "d626027eb78b4b8a90269934fd55328b";

    private final IQApp app = new IQApp(APP_ID);
    private ConnectIQ connectIQ;
    private volatile boolean sdkReady = false;

    public interface MessageSentListener {
        void onMessageSent();
    }

    public void connect(Service service) {
        connectIQ = ConnectIQ.getInstance(service, ConnectIQ.IQConnectType.WIRELESS);
        connectIQ.initialize(service, true, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                UserError.Log.i(TAG, "SDK Ready");
                sdkReady = true;
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                UserError.Log.e(TAG, "Error SDK init: " + status);
                sdkReady = false;
            }

            @Override
            public void onSdkShutDown() {
                sdkReady = false;
            }
        });
    }

    public void disconnect(Service service) {
        if (connectIQ == null) {
            return;
        }
        try {
            connectIQ.shutdown(service);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error shutting down SDK: " + e.getMessage());
        }
        sdkReady = false;
    }

    /**
     * Sends message to every connected device that has our app installed.
     * listener.onMessageSent() fires once per such device, before the
     * (fire-and-forget) send is attempted - callers use it to know at least
     * one device was reachable, not that delivery succeeded.
     */
    public void sendToConnectedDevices(List<Object> message, MessageSentListener listener) {
        if (!sdkReady) {
            return;
        }
        try {
            List<IQDevice> devices = connectIQ.getConnectedDevices();
            UserError.Log.d(TAG, "Devices detected: " + devices.size());
            for (IQDevice device : devices) {
                sendIfAppInstalled(device, message, listener);
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error listing connected devices: " + e.getMessage());
        }
    }

    private void sendIfAppInstalled(IQDevice device, List<Object> message, MessageSentListener listener) {
        try {
            if (device == null || connectIQ.getDeviceStatus(device) != IQDevice.IQDeviceStatus.CONNECTED) {
                return;
            }
            connectIQ.getApplicationInfo(APP_ID, device, new ConnectIQ.IQApplicationInfoListener() {
                @Override
                public void onApplicationInfoReceived(IQApp appInfo) {
                    if (appInfo != null && appInfo.getStatus() == IQApp.IQAppStatus.INSTALLED) {
                        send(device, message, listener);
                    }
                }

                @Override
                public void onApplicationNotInstalled(String applicationId) {
                    UserError.Log.d(TAG, "App not installed on " + device);
                }
            });
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error checking device " + device + ": " + e.getMessage());
        }
    }

    private void send(IQDevice device, List<Object> message, MessageSentListener listener) {
        if (listener != null) {
            listener.onMessageSent();
        }
        try {
            UserError.Log.d(TAG, "Sending message: " + message);
            connectIQ.sendMessage(device, app, message, (d, a, status) -> {});
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error sending message: " + e.getMessage());
        }
    }
}
