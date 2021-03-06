package com.exelerus.cordova.audioinputcapture;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.content.pm.PackageManager;
import android.content.Context;
import org.apache.cordova.PermissionHelper;
import android.Manifest;

public class AudioInputCapture extends CordovaPlugin {
    private static final String LOG_TAG = "AudioInputCapture";

    private CallbackContext callbackContext = null;
    private AudioInputReceiver receiver;
    private final AudioInputCaptureHandler handler = new AudioInputCaptureHandler(this);

    public static String[] permissions = { Manifest.permission.RECORD_AUDIO };
    public static int RECORD_AUDIO = 0;
    public static final int PERMISSION_DENIED_ERROR = 20;

    private int sampleRate = 44100;
    private int bufferSize = 4096;
    private int channels = 1;
    private String format = null;
    private int audioSource = 0;
    private boolean monitoring = false;
    private int monitorSampleRate = 1;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        boolean result = false;
        if (action.equals("start")) {

            if (this.callbackContext != null) {
                callbackContext.error("AudioInputCapture listener already running.");
                return true;
            }

            this.callbackContext = callbackContext;

            try {
                this.sampleRate = args.getInt(0);
                this.bufferSize = args.getInt(1);
                this.channels = args.getInt(2);
                this.format = args.getString(3);
                this.audioSource = args.getInt(4);
                this.monitoring = args.getBoolean(5);
                this.monitorSampleRate = args.getInt(6);

                promptForRecord();
                if (!receiver.isInitialised()) {
                    callbackContext.error("AudioInputCapture listener could not be initialised.");
                    return true;
                }
            } catch (Exception e) {
                receiver.interrupt();

                this.callbackContext
                        .sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return false;
            }

            // Don't return any result now, since status results will be sent when events
            // come in from broadcast receiver
            PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
            pluginResult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginResult);
            return true;
        } else if (action.equals("stop")) {
            receiver.interrupt();
            this.sendUpdate(new JSONObject(), false); // release status callback in JS side
            this.callbackContext = null;
            callbackContext.success();
            return true;
        } else if (action.equals("startRecording")) {
            // just try to set the monitoring in the receiver thread.
            try {
                Context context = this.cordova.getActivity().getApplicationContext();
                String filePath = args.getString(0);
                String fileName = args.getString(1);
                if (receiver != null)
                    receiver.startRecording(filePath, fileName, context);
            } catch (Exception e) {
                Log.e(LOG_TAG, e.getMessage(), e);
                return false;
            }
            callbackContext.success();
            return true;
        } else if (action.equals("finishRecording")) {
            if (receiver != null)
                receiver.finishRecording();
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
            pluginResult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginResult);
            callbackContext.success();
            return true;
        } else if (action.equals("setMonitoring")) {
            // just try to set the monitoring in the receiver thread.
            try {
                this.monitoring = args.getBoolean(0);
                if (receiver != null)
                    receiver.setMonitoring(this.monitoring);
            } catch (Exception e) {
                Log.e(LOG_TAG, e.getMessage(), e);
                return false;
            }
            callbackContext.success();
            return true;
        } else if (action.equals("listSources")) {
            Context context = this.cordova.getActivity().getApplicationContext();
            AudioInputReceiver receiver = new AudioInputReceiver();
            JSONArray sources = receiver.getSourcesList(context);

            callbackContext.success(sources.toString());
            callbackContext.error("listSources called");
            return true;
        }
        return false;
    }

    public void onDestroy() {
        if (!receiver.isInterrupted()) {
            receiver.interrupt();
        }
    }

    public void onReset() {
        if (!receiver.isInterrupted()) {
            receiver.interrupt();
        }
    }

    /**
     * Create a new plugin result and send it back to JavaScript
     */
    private void sendUpdate(JSONObject info, boolean keepCallback) {
        if (this.callbackContext != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, info);
            result.setKeepCallback(keepCallback);
            this.callbackContext.sendPluginResult(result);
        }
    }

    private static class AudioInputCaptureHandler extends Handler {
        private final WeakReference<AudioInputCapture> mActivity;

        public AudioInputCaptureHandler(AudioInputCapture activity) {
            mActivity = new WeakReference<AudioInputCapture>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            AudioInputCapture activity = mActivity.get();
            if (activity != null) {
                JSONObject info = new JSONObject();
                try {
                    info.put("data", msg.getData().getString("data"));
                } catch (JSONException e) {
                    Log.e(LOG_TAG, e.getMessage(), e);
                }
                activity.sendUpdate(info, true);
            }
        }
    }

    /**
     * Prompt user for record audio permission
     */
    protected void getMicPermission(int requestCode) {
        PermissionHelper.requestPermission(this, requestCode, permissions[RECORD_AUDIO]);
    }

    /**
     * Ensure that we have gotten record audio permission
     */
    private void promptForRecord() {
        if (PermissionHelper.hasPermission(this, permissions[RECORD_AUDIO])) {
            receiver = new AudioInputReceiver(this.sampleRate, this.bufferSize, this.channels, this.format,
                    this.audioSource, this.monitoring, this.monitorSampleRate);
            receiver.setHandler(handler);
            receiver.start();
        } else {
            getMicPermission(RECORD_AUDIO);
        }
    }

    /**
     * Handle request permission result
     */
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
            throws JSONException {

        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.callbackContext
                        .sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return;
            }
        }

        promptForRecord();
    }
}