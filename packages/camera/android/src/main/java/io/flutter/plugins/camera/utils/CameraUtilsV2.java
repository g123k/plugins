package io.flutter.plugins.camera.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;

import androidx.annotation.RequiresApi;
import androidx.core.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.flutter.plugins.camera.builders.AvailableCameraBuilder;

/**
 * Provides various utilities for camera.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public final class CameraUtilsV2 {

    private CameraUtilsV2() {
    }

    public static Size[] computeBestPreviewAndRecordingSize(
            Activity activity,
            StreamConfigurationMap streamConfigurationMap,
            int minHeight,
            int orientation,
            Size captureSize) {
        Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);

        List<CameraUtils.LocalSize> localSizes = new ArrayList<>(sizes.length);
        for (Size size : sizes) {
            localSizes.add(new CameraUtils.LocalSize(size));
        }

        Pair<CameraUtils.LocalSize, CameraUtils.LocalSize> res = CameraUtils.computeBestPreviewAndRecordingSize(activity,
                localSizes,
                minHeight,
                orientation,
                new CameraUtils.LocalSize(captureSize));

        return new Size[]{
                res.first.toSize(),
                res.second.toSize()};
    }

    public static Size computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
        // For still image captures, we use the largest available size.
        return Collections.max(
                Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
                new CompareSizesByArea());
    }

    static List<AvailableCameraBuilder> getAvailableCameras(Activity activity)
            throws CameraAccessException {
        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        String[] cameraNames = cameraManager.getCameraIdList();
        List<AvailableCameraBuilder> cameras = new ArrayList<>();
        for (String cameraName : cameraNames) {
            AvailableCameraBuilder builder = new AvailableCameraBuilder();

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
            int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            builder.setName(cameraName)
                    .setOrientation(sensorOrientation);

            int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            switch (lensFacing) {
                case CameraMetadata.LENS_FACING_FRONT:
                    builder.setTypeAsFront();
                    break;
                case CameraMetadata.LENS_FACING_BACK:
                    builder.setTypeAsBack();
                    break;
                case CameraMetadata.LENS_FACING_EXTERNAL:
                    builder.setTypeAsExternal();
                    break;
            }
            cameras.add(builder);
        }
        return cameras;
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
