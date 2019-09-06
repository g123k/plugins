package io.flutter.plugins.camera.utils;

import android.app.Activity;
import android.hardware.Camera;

import androidx.core.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.flutter.plugins.camera.builders.AvailableCameraBuilder;

/**
 * Provides various utilities for camera V1 (pre Lollipop).
 */
public final class CameraUtilsV1 {

    private CameraUtilsV1() {
    }

    static List<AvailableCameraBuilder> getAvailableCameras() {
        int numberOfCameras = Camera.getNumberOfCameras();
        List<AvailableCameraBuilder> cameras = new ArrayList<>(numberOfCameras);

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);

            AvailableCameraBuilder builder = new AvailableCameraBuilder();
            builder.setName(String.valueOf(i));

            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                builder.setTypeAsBack();
            } else {
                builder.setTypeAsFront();
            }

            builder.setOrientation(cameraInfo.orientation);

            cameras.add(builder);
        }

        return cameras;
    }

    public static Pair<Camera.Size, Camera.Size> computeBestPreviewAndRecordingSize(Activity activity,
                                                                         List<Camera.Size> sizes,
                                                                         int minHeight,
                                                                         int orientation,
                                                                         Camera.Size captureSize) {
        List<CameraUtils.LocalSize> localSizes = new ArrayList<>(sizes.size());
        for (Camera.Size size : sizes) {
            localSizes.add(new CameraUtils.LocalSize(size));
        }

        Pair<CameraUtils.LocalSize, CameraUtils.LocalSize> res = CameraUtils.computeBestPreviewAndRecordingSize(activity,
                localSizes,
                minHeight,
                orientation,
                new CameraUtils.LocalSize(captureSize));

        return new Pair<>(
                find(sizes, res.first),
                find(sizes, res.second)
        );
    }

    private static Camera.Size find(List<Camera.Size> sizes, CameraUtils.LocalSize searchedSize) {
        for (Camera.Size size : sizes) {
            if (size.width == searchedSize.width && size.height == searchedSize.height) {
                return size;
            }
        }

        return null;
    }

    static class CompareSizesByArea implements Comparator<Camera.Size> {
        @Override
        public int compare(Camera.Size lhs, Camera.Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.width * lhs.height - (long) rhs.width * rhs.height);
        }
    }

}
