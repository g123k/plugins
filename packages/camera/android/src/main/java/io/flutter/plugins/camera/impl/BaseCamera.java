package io.flutter.plugins.camera.impl;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.camera.utils.CameraUtils;
import io.flutter.view.FlutterView;
import io.flutter.view.TextureRegistry;

public abstract class BaseCamera {

    final Activity activity;
    final TextureRegistry.SurfaceTextureEntry flutterTexture;
    final int minHeight;

    final boolean enableAudio;
    final boolean enableTorch;
    final boolean enableAE;

    EventChannel.EventSink eventSink;

    Handler backgroundHandler;
    private HandlerThread backgroundThread;

    BaseCamera(final Activity activity,
               final FlutterView flutterView,
               final String cameraName,
               final String resolutionPreset,
               final boolean enableAudio,
               final boolean enableTorch,
               final boolean enableAE) {
        this.activity = activity;
        this.flutterTexture = flutterView.createSurfaceTexture();
        this.minHeight = CameraUtils.computeMinHeight(resolutionPreset);

        this.enableAudio = enableAudio;
        this.enableTorch = enableTorch;
        this.enableAE = enableAE;
    }

    public final void setupCameraEventChannel(EventChannel cameraEventChannel) {
        cameraEventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object arguments, EventChannel.EventSink sink) {
                        eventSink = sink;
                    }

                    @Override
                    public void onCancel(Object arguments) {
                        eventSink = null;
                    }
                });
    }

    public abstract void open(@NonNull final MethodChannel.Result result) throws Exception;

    public final void takePicture(String filePath, @NonNull final MethodChannel.Result result) {
        final File file = new File(filePath);

        if (file.exists()) {
            result.error(
                    "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
            return;
        }

        onTakePicture(filePath, result);
    }

    abstract void onTakePicture(String filePath, @NonNull final MethodChannel.Result result);

    public abstract void startVideoRecording(String filePath, MethodChannel.Result result);

    public abstract void stopVideoRecording(@NonNull final MethodChannel.Result result);

    public abstract void startPreview() throws Exception;

    public abstract void startPreviewWithImageStream(EventChannel imageStreamChannel) throws Exception;

    public abstract void setTorchMode(@NonNull final MethodChannel.Result result, boolean enable);

    public abstract void setTorchMode(@NonNull final MethodChannel.Result result, boolean enable, double level);

    public abstract void setAEMode(@NonNull final MethodChannel.Result result, boolean enable);

    public TextureRegistry.SurfaceTextureEntry getFlutterTexture() {
        return flutterTexture;
    }

    void sendEvent(EventType eventType) {
        sendEvent(eventType, null);
    }

    void sendEvent(EventType eventType, String description) {
        if (eventSink != null) {
            Map<String, String> event = new HashMap<>();
            event.put("eventType", eventType.toString().toLowerCase());
            // Only errors have description
            if (eventType != EventType.ERROR) {
                event.put("errorDescription", description);
            }
            eventSink.success(event);
        }
    }

    public abstract void close();

    public void dispose() {
        close();
        flutterTexture.release();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    void stopBackgroundThread() {
        if (backgroundThread != null) {

            final int version = Build.VERSION.SDK_INT;
            if (version >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                backgroundThread.quitSafely();
            } else {
                backgroundThread.quit();
            }
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void runOnUIThread(Runnable runnable) {
        activity.runOnUiThread(runnable);
    }

    enum EventType {
        ERROR,
        CAMERA_CLOSING,
    }

}
