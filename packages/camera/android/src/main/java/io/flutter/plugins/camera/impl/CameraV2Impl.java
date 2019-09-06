package io.flutter.plugins.camera.impl;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.camera.CameraImageSaver;
import io.flutter.plugins.camera.builders.CameraOpenResultBuilder;
import io.flutter.plugins.camera.utils.CameraUtils;
import io.flutter.plugins.camera.utils.CameraUtilsV2;
import io.flutter.view.FlutterView;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraV2Impl extends BaseCamera {

    private final CameraManager cameraManager;
    private final OrientationEventListener orientationEventListener;
    private final boolean isFrontFacing;
    private final int sensorOrientation;
    private final String cameraName;
    private final Size captureSize;
    private final Size previewSize;
    private final Size videoSize;

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader pictureImageReader;
    private ImageReader imageStreamReader;
    private EventChannel.EventSink eventSink;
    private CaptureRequest.Builder captureRequestBuilder;
    private MediaRecorder mediaRecorder;
    private boolean recordingVideo;
    private int currentOrientation = ORIENTATION_UNKNOWN;

    public CameraV2Impl(Activity activity, FlutterView flutterView, String cameraName, String resolutionPreset, boolean enableAudio, boolean enableTorch, boolean enableAE) throws CameraAccessException {
        super(activity, flutterView, cameraName, resolutionPreset, enableAudio, enableTorch, enableAE);

        if (activity == null) {
            throw new IllegalStateException("No activity available!");
        }

        this.cameraName = cameraName;
        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        orientationEventListener =
                new OrientationEventListener(activity.getApplicationContext()) {
                    @Override
                    public void onOrientationChanged(int i) {
                        if (i == ORIENTATION_UNKNOWN) {
                            return;
                        }
                        // Convert the raw deg angle to the nearest multiple of 90.
                        currentOrientation = (int) Math.round(i / 90.0) * 90;
                    }
                };
        orientationEventListener.enable();

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
        StreamConfigurationMap streamConfigurationMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        //noinspection ConstantConditions
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //noinspection ConstantConditions
        isFrontFacing =
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
        captureSize = CameraUtilsV2.computeBestCaptureSize(streamConfigurationMap);
        Size[] sizes =
                CameraUtilsV2.computeBestPreviewAndRecordingSize(
                        activity, streamConfigurationMap, CameraUtils.computeMinHeight(resolutionPreset), getMediaOrientation(), captureSize);
        videoSize = sizes[0];
        previewSize = sizes[1];
    }

    private void prepareMediaRecorder(String outputFilePath) throws IOException {
        if (mediaRecorder != null) {
            mediaRecorder.release();
        }
        mediaRecorder = new MediaRecorder();

        // There's a specific order that mediaRecorder expects. Do not change the order
        // of these function calls.
        if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (enableAudio) mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(1024 * 1000);
        if (enableAudio) mediaRecorder.setAudioSamplingRate(16000);
        mediaRecorder.setVideoFrameRate(27);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setOutputFile(outputFilePath);
        mediaRecorder.setOrientationHint(getMediaOrientation());

        mediaRecorder.prepare();
    }

    @Override
    public void open(@NonNull MethodChannel.Result result) throws CameraAccessException {
        pictureImageReader =
                ImageReader.newInstance(
                        captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

        // Used to steam image byte data to dart side.
        imageStreamReader =
                ImageReader.newInstance(
                        previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

        cameraManager.openCamera(
                cameraName,
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        cameraDevice = device;
                        try {
                            startPreview();
                        } catch (CameraAccessException e) {
                            result.error("CameraAccess", e.getMessage(), null);
                            close();
                            return;
                        }

                        CameraOpenResultBuilder builder = new CameraOpenResultBuilder();
                        builder.setTextureId(flutterTexture.id());
                        builder.setPreviewWidth(previewSize.getWidth());
                        builder.setPreviewHeight(previewSize.getHeight());

                        result.success(builder.build());
                    }

                    @Override
                    public void onClosed(@NonNull CameraDevice camera) {
                        sendEvent(EventType.CAMERA_CLOSING);
                        super.onClosed(camera);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                        close();
                        sendEvent(EventType.ERROR, "The camera was disconnected.");
                    }

                    @Override
                    public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                        close();
                        String errorDescription;
                        switch (errorCode) {
                            case ERROR_CAMERA_IN_USE:
                                errorDescription = "The camera device is in use already.";
                                break;
                            case ERROR_MAX_CAMERAS_IN_USE:
                                errorDescription = "Max cameras in use";
                                break;
                            case ERROR_CAMERA_DISABLED:
                                errorDescription = "The camera device could not be opened due to a device policy.";
                                break;
                            case ERROR_CAMERA_DEVICE:
                                errorDescription = "The camera device has encountered a fatal error";
                                break;
                            case ERROR_CAMERA_SERVICE:
                                errorDescription = "The camera service has encountered a fatal error.";
                                break;
                            default:
                                errorDescription = "Unknown camera error";
                        }
                        sendEvent(EventType.ERROR, errorDescription);
                    }
                },
                null);
    }

    @Override
    public void onTakePicture(String filePath, @NonNull MethodChannel.Result result) {
        pictureImageReader.setOnImageAvailableListener(
                reader -> {
                    Image image = reader.acquireLatestImage();

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    writeToFile(buffer, new File(filePath), new CameraImageSaver.EventListener() {
                        @Override
                        public void onImageSaved(File file) {
                            runOnUIThread(() -> result.success(null));
                        }

                        @Override
                        public void onError(Exception exception) {
                            runOnUIThread(() -> result.error("IOError", "Failed saving image", null));
                        }
                    });
                },
                null);

        try {
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(pictureImageReader.getSurface());
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

            cameraCaptureSession.capture(
                    captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureFailed(
                                @NonNull CameraCaptureSession session,
                                @NonNull CaptureRequest request,
                                @NonNull CaptureFailure failure) {
                            String reason;
                            switch (failure.getReason()) {
                                case CaptureFailure.REASON_ERROR:
                                    reason = "An error happened in the framework";
                                    break;
                                case CaptureFailure.REASON_FLUSHED:
                                    reason = "The capture has failed due to an abortCaptures() call";
                                    break;
                                default:
                                    reason = "Unknown reason";
                            }
                            result.error("captureFailure", reason, null);
                        }
                    },
                    null);
        } catch (CameraAccessException e) {
            result.error("cameraAccess", e.getMessage(), null);
        }
    }

    private void createCaptureSession(int templateType, Surface... surfaces)
            throws CameraAccessException {
        createCaptureSession(templateType, null, surfaces);
    }

    private void createCaptureSession(
            int templateType, Runnable onSuccessCallback, Surface... surfaces)
            throws CameraAccessException {
        // Close any existing capture session.
        closeCaptureSession();

        // Create a new capture builder.
        captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

        // Build Flutter surface to render to
        SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface flutterSurface = new Surface(surfaceTexture);
        captureRequestBuilder.addTarget(flutterSurface);

        List<Surface> remainingSurfaces = Arrays.asList(surfaces);
        if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
            // If it is not preview mode, add all surfaces as targets.
            for (Surface surface : remainingSurfaces) {
                captureRequestBuilder.addTarget(surface);
            }
        }

        // Torch
        captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                enableTorch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

        // Auto Exposure
        captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                enableAE ? CaptureRequest.CONTROL_AE_MODE_ON : CaptureRequest.CONTROL_AE_MODE_OFF);

        // Prepare the callback
        CameraCaptureSession.StateCallback callback =
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            if (cameraDevice == null) {
                                sendEvent(EventType.ERROR, "The camera was closed during configuration.");
                                return;
                            }
                            cameraCaptureSession = session;
                            captureRequestBuilder.set(
                                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                            // // Torch
                            // captureRequestBuilder.set(
                            //   CaptureRequest.FLASH_MODE,
                            //   enableTorch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                            // // Auto Exposure
                            // captureRequestBuilder.set(
                            //   CaptureRequest.CONTROL_AE_MODE,
                            //   enableAE ? CaptureRequest.CONTROL_AE_MODE_ON : CaptureRequest.CONTROL_AE_MODE_OFF);

                            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            if (onSuccessCallback != null) {
                                onSuccessCallback.run();
                            }
                        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                            sendEvent(EventType.ERROR, e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        sendEvent(EventType.ERROR, "Failed to configure camera session.");
                    }
                };

        // Collect all surfaces we want to render to.
        List<Surface> surfaceList = new ArrayList<>();
        surfaceList.add(flutterSurface);
        surfaceList.addAll(remainingSurfaces);
        // Start the session
        cameraDevice.createCaptureSession(surfaceList, callback, null);
    }

    @Override
    public void startVideoRecording(String filePath, MethodChannel.Result result) {
        if (new File(filePath).exists()) {
            result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
            return;
        }
        try {
            prepareMediaRecorder(filePath);
            recordingVideo = true;
            createCaptureSession(
                    CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
            result.success(null);
        } catch (CameraAccessException | IOException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    @Override
    public void stopVideoRecording(@NonNull MethodChannel.Result result) {
        if (!recordingVideo) {
            result.success(null);
            return;
        }

        try {
            recordingVideo = false;
            mediaRecorder.stop();
            mediaRecorder.reset();
            startPreview();
            result.success(null);
        } catch (CameraAccessException | IllegalStateException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    @Override
    public void startPreview() throws CameraAccessException {
        createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
    }

    @Override
    public void startPreviewWithImageStream(EventChannel imageStreamChannel) throws CameraAccessException {
        createCaptureSession(CameraDevice.TEMPLATE_STILL_CAPTURE, imageStreamReader.getSurface());

        imageStreamChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
                        setImageStreamImageAvailableListener(imageStreamSink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        imageStreamReader.setOnImageAvailableListener(null, null);
                    }
                });
    }

    private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
        imageStreamReader.setOnImageAvailableListener(
                reader -> {
                    Image img = reader.acquireLatestImage();
                    if (img == null) return;

                    List<Map<String, Object>> planes = new ArrayList<>();
                    for (Image.Plane plane : img.getPlanes()) {
                        ByteBuffer buffer = plane.getBuffer();

                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes, 0, bytes.length);

                        Map<String, Object> planeBuffer = new HashMap<>();
                        planeBuffer.put("bytesPerRow", plane.getRowStride());
                        planeBuffer.put("bytesPerPixel", plane.getPixelStride());
                        planeBuffer.put("bytes", bytes);

                        planes.add(planeBuffer);
                    }

                    Map<String, Object> imageBuffer = new HashMap<>();
                    imageBuffer.put("width", img.getWidth());
                    imageBuffer.put("height", img.getHeight());
                    imageBuffer.put("format", img.getFormat());
                    imageBuffer.put("planes", planes);

                    imageStreamSink.success(imageBuffer);
                    img.close();
                },
                null);
    }

    @Override
    public void setTorchMode(@NonNull MethodChannel.Result result, boolean enable) {
        setTorchMode(result, enable, 1.0);
    }

    @Override
    public void setTorchMode(@NonNull MethodChannel.Result result, boolean enable, double level) {
        try {
            captureRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    enable ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);

            result.success(null);
        } catch (Exception e) {
            result.error("cameraTorchFailed", e.getMessage(), null);
        }
    }

    @Override
    public void setAEMode(@NonNull MethodChannel.Result result, boolean enable) {
        try {
            // Auto Exposure
            captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    enable ? CaptureRequest.CONTROL_AE_MODE_ON : CaptureRequest.CONTROL_AE_MODE_OFF);

            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);

            result.success(null);
        } catch (Exception e) {
            result.error("cameraAEFailed", e.getMessage(), null);
        }
    }

    private void closeCaptureSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
    }

    @Override
    public void close() {
        closeCaptureSession();

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (pictureImageReader != null) {
            pictureImageReader.close();
            pictureImageReader = null;
        }
        if (imageStreamReader != null) {
            imageStreamReader.close();
            imageStreamReader = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }

        stopBackgroundThread();
    }

    @Override
    public void dispose() {
        super.dispose();
        orientationEventListener.disable();
    }

    private int getMediaOrientation() {
        final int sensorOrientationOffset =
                (currentOrientation == ORIENTATION_UNKNOWN)
                        ? 0
                        : (isFrontFacing) ? -currentOrientation : currentOrientation;
        return (sensorOrientationOffset + sensorOrientation + 360) % 360;
    }

    private void writeToFile(ByteBuffer buffer, File file, CameraImageSaver.EventListener listener) {
        if (backgroundHandler == null) {
            startBackgroundThread();
        }

        backgroundHandler.post(new CameraImageSaver(buffer, file, listener));
    }

}
