package io.flutter.plugins.camera.impl;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.camera.CameraImageSaver;
import io.flutter.plugins.camera.builders.CameraOpenResultBuilder;
import io.flutter.plugins.camera.utils.CameraUtilsV1;
import io.flutter.view.FlutterView;
import io.flutter.view.TextureRegistry;

public class CameraV1Impl extends BaseCamera {

    private final int cameraId;

    private Camera camera;
    private boolean cameraReleased;

    private MediaRecorder mediaRecorder;

    private final Object cameraLock = new Object();

    public CameraV1Impl(Activity activity, FlutterView flutterView, String cameraName, String resolutionPreset, boolean enableAudio, boolean enableTorch, boolean enableAE) {
        super(activity, flutterView, cameraName, resolutionPreset, enableAudio, enableTorch, enableAE);

        this.cameraId = Integer.parseInt(cameraName);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    @Override
    public void open(@NonNull MethodChannel.Result result) throws IOException {
        synchronized (cameraLock) {
            startBackgroundThread();

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);

            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            cameraReleased = false;

            initOrientation();

            CameraOpenResultBuilder resBuilder = new CameraOpenResultBuilder()
                    .setTextureId(flutterTexture.id());

            Pair<Integer, Integer> previewSize;

            try {
                Pair<Camera.Size, Camera.Size> sizes = CameraUtilsV1.computeBestPreviewAndRecordingSize(
                        activity,
                        parameters.getSupportedPreviewSizes(),
                        minHeight,
                        info.orientation,
                        parameters.getPictureSize());

                parameters.setPreviewSize(sizes.first.width, sizes.first.height);
                parameters.setPictureSize(sizes.second.width, sizes.second.height);

                camera.setParameters(parameters);
                previewSize = new Pair<>(sizes.second.width, sizes.second.height);

            } catch (Exception e) {
                previewSize = getPreviewSize(parameters.getPreviewSize());

            }

            startPreview();

            result.success(resBuilder
                    .setPreviewWidth(previewSize.first)
                    .setPreviewHeight(previewSize.second)
                    .build());
        }
    }

    public void startPreview() throws IOException {
        camera.setPreviewTexture(flutterTexture.surfaceTexture());
        camera.startPreview();
    }

    public void startPreviewWithImageStream(EventChannel imageStreamChannel) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();

        imageStreamChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object o, EventChannel.EventSink eventSink) {
                camera.setPreviewCallback(new Camera.PreviewCallback() {
                    @Override
                    public void onPreviewFrame(byte[] data, Camera camera) {
                        List<Map<String, Object>> planes = new ArrayList<>();

                        Map<String, Object> planeBuffer = new HashMap<>();
                        //planeBuffer.put("bytesPerRow", plane.getRowStride());
                        //planeBuffer.put("bytesPerPixel", plane.getPixelStride());

                        planeBuffer.put("bytesPerRow", 1);
                        planeBuffer.put("bytesPerPixel", previewSize.width);
                        planeBuffer.put("bytes", data);

                        planes.add(planeBuffer);

                        Map<String, Object> imageBuffer = new HashMap<>();
                        imageBuffer.put("width", previewSize.width);
                        imageBuffer.put("height", previewSize.height);
                        imageBuffer.put("format", ImageFormat.YUV_420_888);
                        imageBuffer.put("planes", planes);

                        eventSink.success(imageBuffer);
                    }
                });
            }

            @Override
            public void onCancel(Object o) {
                camera.setPreviewCallback(null);
            }
        });
    }

    @Override
    public void onTakePicture(String filePath, @NonNull MethodChannel.Result result) {
        camera.cancelAutoFocus();
        camera.autoFocus((success, camera) -> camera.takePicture(null, null,
                (data, camera1) -> {
                    backgroundHandler.post(new CameraImageSaver(data, new File(filePath),
                            new CameraImageSaver.EventListener() {

                                @Override
                                public void onImageSaved(File file) {
                                    runOnUIThread(() -> result.success(null));

                                }

                                @Override
                                public void onError(Exception exception) {
                                    runOnUIThread(() -> result.error("IOError", "Failed saving image", null));
                                }
                            }));

                    // Continue the preview
                    camera.startPreview();
                }));
    }

    @Override
    public void startVideoRecording(String filePath, MethodChannel.Result result) {
        try {
            if (mediaRecorder != null) {
                result.error("videoRecordingFailed", "Already recording", null);
                return;
            }

            mediaRecorder = new MediaRecorder();
            camera.unlock();

            mediaRecorder.setCamera(camera);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            if (enableAudio) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }

            mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
            mediaRecorder.setOutputFile(filePath);

            mediaRecorder.prepare();
            mediaRecorder.start();

            result.success(null);
        } catch (Exception e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    @Override
    public void stopVideoRecording(@NonNull MethodChannel.Result result) {
        if (mediaRecorder == null) {
            result.success(null);
            return;
        }

        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;

            camera.startPreview();
            result.success(null);
        } catch (IllegalStateException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    public void setTorchMode(@NonNull final MethodChannel.Result result, boolean enable) {
        if (camera == null) {
            result.error("cameraTorchFailed", "", null);
            return;
        }

        Camera.Parameters parameters = camera.getParameters();
        parameters.setFlashMode(enable ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
        camera.setParameters(parameters);
    }

    public void setTorchMode(@NonNull final MethodChannel.Result result, boolean enable, double level) {
        // Camera 1 does not support the level
        setTorchMode(result, enable);
    }

    public void setAEMode(@NonNull final MethodChannel.Result result, boolean enable) {
        if (camera == null) {
            result.error("cameraAEFailed", "", null);
            return;
        }

        Camera.Parameters parameters = camera.getParameters();
        parameters.setAutoExposureLock(enable);
        camera.setParameters(parameters);
    }

    public TextureRegistry.SurfaceTextureEntry getFlutterTexture() {
        return flutterTexture;
    }

    public void close() {
        synchronized (cameraLock) {
            if (cameraReleased) {
                return;
            }

            cameraReleased = true;

            stopBackgroundThread();

            if (camera != null) {
                camera.cancelAutoFocus();
                camera.stopPreview();
                camera.release();
                camera = null;
            }

            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        }
    }

    private Pair<Integer, Integer> getPreviewSize(Camera.Size size) {
        switch (activity.getResources().getConfiguration().orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                return new Pair<>(size.height, size.height);
            case Configuration.ORIENTATION_PORTRAIT:
            default:
                return new Pair<>(size.width, size.height);
        }
    }

    private void initOrientation() {
        int angle;

        Display display = activity.getWindowManager().getDefaultDisplay();
        switch (display.getRotation()) {
            case Surface.ROTATION_90:
                angle = 0;
                break;
            case Surface.ROTATION_180:
                angle = 270;
                break;
            case Surface.ROTATION_270:
                angle = 180;
                break;
            case Surface.ROTATION_0:
            default:
                angle = 90;
        }

        camera.setDisplayOrientation(angle);
    }

}
