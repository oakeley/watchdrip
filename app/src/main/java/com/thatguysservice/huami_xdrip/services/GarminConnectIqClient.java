package com.thatguysservice.huami_xdrip.services;

import android.app.Service;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.thatguysservice.huami_xdrip.models.GarminConnectionStatus;
import com.thatguysservice.huami_xdrip.models.database.UserError;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin wrapper around the Garmin ConnectIQ SDK: connects/disconnects the SDK
 * and sends BG-data messages to every connected device that has our
 * companion app installed. Not thread-safe beyond what the ConnectIQ SDK
 * itself guarantees.
 *
 * connect() is async - the SDK isn't ready to send until onSdkReady() fires,
 * which can take a moment after every service (re)start. sendToConnectedDevices()
 * used to silently drop the send if called before that happened, which was the
 * common case on every fresh GarminService start. It now queues the most recent
 * pending send and flushes it as soon as the SDK becomes ready.
 *
 * The watch confirms receipt out-of-band via an HTTP ping to this app's local
 * WebServer (see GarminConnectionStatus / MiBandService's /garmin_ack CGI
 * endpoint) rather than through this class - a prior attempt to use
 * Communications.transmit()/registerForAppEvents() for that never received
 * anything on the Android side despite the watch send succeeding, and no
 * Connect IQ sample pairs those two APIs, so it was dropped in favor of the
 * proven-working localhost-HTTP-via-Garmin-Connect-proxy pattern instead.
 */
public class GarminConnectIqClient {
    private static final String TAG = GarminConnectIqClient.class.getSimpleName();
    private static final String APP_ID = "d626027eb78b4b8a90269934fd55328b";

    private final IQApp app = new IQApp(APP_ID);
    private ConnectIQ connectIQ;
    private volatile boolean sdkReady = false;

    // Guards against a stale async callback (from a since-superseded send)
    // mutating state for a newer one - see sendToConnectedDevices().
    private volatile long currentGeneration = 0;

    private List<Object> pendingMessage;
    private MessageSentListener pendingListener;

    public interface MessageSentListener {
        void onMessageSent();
        void onMessageFailed(String reason);
    }

    public void connect(Service service) {
        connectIQ = ConnectIQ.getInstance(service, ConnectIQ.IQConnectType.WIRELESS);
        connectIQ.initialize(service, true, new ConnectIQ.ConnectIQListener() {
            @Override
            public void onSdkReady() {
                UserError.Log.i(TAG, "SDK Ready");
                sdkReady = true;
                flushPendingSend();
            }

            @Override
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                UserError.Log.e(TAG, "Error SDK init: " + status);
                sdkReady = false;
                failPending("SDK init error: " + status);
            }

            @Override
            public void onSdkShutDown() {
                sdkReady = false;
            }
        });
    }

    private synchronized void flushPendingSend() {
        if (pendingMessage == null) {
            return;
        }
        List<Object> message = pendingMessage;
        MessageSentListener listener = pendingListener;
        pendingMessage = null;
        pendingListener = null;
        UserError.Log.d(TAG, "Flushing queued send now that SDK is ready");
        doSend(message, listener);
    }

    private synchronized void failPending(String reason) {
        if (pendingListener != null) {
            pendingListener.onMessageFailed(reason);
        }
        pendingMessage = null;
        pendingListener = null;
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
        GarminConnectionStatus.clear();
    }

    /**
     * Sends message to every connected device that has our app installed.
     * If the SDK isn't ready yet, the message is queued (replacing any
     * earlier not-yet-sent message) and sent as soon as onSdkReady() fires,
     * instead of being silently dropped.
     */
    public synchronized void sendToConnectedDevices(List<Object> message, MessageSentListener listener) {
        long generation = ++currentGeneration;
        MessageSentListener guarded = guard(generation, listener);
        if (!sdkReady) {
            UserError.Log.d(TAG, "SDK not ready yet, queuing send");
            pendingMessage = message;
            pendingListener = guarded;
            return;
        }
        doSend(message, guarded);
    }

    // Wraps a listener so that only the first callback for its generation is
    // delivered, and only if no newer send has started since. Without this,
    // a late callback for a superseded message (e.g. an old CMD_UPDATE_BG
    // still waiting on getApplicationInfo() when a newer one is sent) could
    // clear GarminService's pendingJson/lastSentTime for the wrong message,
    // or a second connected device's failure could re-trigger a retry after
    // a first device already succeeded.
    private MessageSentListener guard(long generation, MessageSentListener delegate) {
        AtomicBoolean settled = new AtomicBoolean(false);
        return new MessageSentListener() {
            @Override
            public void onMessageSent() {
                if (generation != currentGeneration || !settled.compareAndSet(false, true)) {
                    return;
                }
                if (delegate != null) {
                    delegate.onMessageSent();
                }
            }

            @Override
            public void onMessageFailed(String reason) {
                if (generation != currentGeneration || !settled.compareAndSet(false, true)) {
                    return;
                }
                if (delegate != null) {
                    delegate.onMessageFailed(reason);
                }
            }
        };
    }

    private void doSend(List<Object> message, MessageSentListener listener) {
        try {
            List<IQDevice> devices = connectIQ.getConnectedDevices();
            UserError.Log.d(TAG, "Devices detected: " + devices.size());
            boolean anyConnected = false;
            for (IQDevice device : devices) {
                if (device != null && connectIQ.getDeviceStatus(device) == IQDevice.IQDeviceStatus.CONNECTED) {
                    anyConnected = true;
                }
                sendIfAppInstalled(device, message, listener);
            }
            if (!anyConnected && listener != null) {
                listener.onMessageFailed("No connected device found (" + devices.size() + " known)");
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error listing connected devices: " + e.getMessage());
            if (listener != null) {
                listener.onMessageFailed("Error listing devices: " + e.getMessage());
            }
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
                    } else {
                        UserError.Log.d(TAG, "App not installed (status) on " + device);
                        if (listener != null) {
                            listener.onMessageFailed("Watch app not installed on " + device);
                        }
                    }
                }

                @Override
                public void onApplicationNotInstalled(String applicationId) {
                    UserError.Log.d(TAG, "App not installed on " + device);
                    if (listener != null) {
                        listener.onMessageFailed("Watch app not installed on " + device);
                    }
                }
            });
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error checking device " + device + ": " + e.getMessage());
            if (listener != null) {
                listener.onMessageFailed("Error checking device: " + e.getMessage());
            }
        }
    }

    private void send(IQDevice device, List<Object> message, MessageSentListener listener) {
        try {
            UserError.Log.d(TAG, "Sending message: " + message);
            connectIQ.sendMessage(device, app, message, (d, a, status) -> {
                UserError.Log.d(TAG, "Send status for " + d + ": " + status);
                if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                    if (listener != null) {
                        listener.onMessageSent();
                    }
                } else {
                    if (listener != null) {
                        listener.onMessageFailed("Send status: " + status);
                    }
                }
            });
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error sending message: " + e.getMessage());
            if (listener != null) {
                listener.onMessageFailed("Error sending: " + e.getMessage());
            }
        }
    }
}
