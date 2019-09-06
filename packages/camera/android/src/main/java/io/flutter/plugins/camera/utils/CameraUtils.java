package io.flutter.plugins.camera.utils;

import android.app.Activity;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Build;
import android.util.Size;
import android.view.Display;

import androidx.annotation.RequiresApi;
import androidx.core.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.flutter.plugins.camera.builders.AvailableCameraBuilder;

/**
 * Provides various utilities for camera.
 */
public final class CameraUtils {

    private CameraUtils() {
    }

    public static List<Map<String, Object>> getAvailableCameras(Activity activity) throws Exception {
        List<AvailableCameraBuilder> cameras;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cameras = CameraUtilsV2.getAvailableCameras(activity);
        } else {
            cameras = CameraUtilsV1.getAvailableCameras();
        }

        List<Map<String, Object>> list = new ArrayList<>(cameras.size());
        for (AvailableCameraBuilder camera : cameras) {
            list.add(camera.build());
        }

        return list;
    }

    public static int computeMinHeight(String resolutionPreset) {
        switch (resolutionPreset) {
            case "high":
                return 720;
            case "medium":
                return 480;
            case "low":
                return 240;
            default:
                throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
        }
    }

    static Pair<LocalSize, LocalSize> computeBestPreviewAndRecordingSize(
            Activity activity,
            List<LocalSize> sizes,
            int minHeight,
            int orientation,
            LocalSize captureSize) {
        LocalSize previewSize, videoSize;

        // Preview size and video size should not be greater than screen resolution or 1080.
        Point screenResolution = new Point();

        Display display = activity.getWindowManager().getDefaultDisplay();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display.getRealSize(screenResolution);
        } else {
            display.getSize(screenResolution);
        }

        final boolean swapWH = shouldInvertOrientation(orientation);
        int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
        int screenHeight = swapWH ? screenResolution.x : screenResolution.y;

        List<LocalSize> goodEnough = new ArrayList<>();
        for (LocalSize s : sizes) {
            if (minHeight <= s.height
                    && s.width <= screenWidth
                    && s.height <= screenHeight
                    && s.height <= 1080) {
                goodEnough.add(s);
            }
        }

        Collections.sort(goodEnough, new CompareSizesByArea());

        if (goodEnough.isEmpty()) {
            previewSize = sizes.get(0);
            videoSize = sizes.get(0);
        } else {
            float captureSizeRatio = (float) captureSize.width / captureSize.height;

            previewSize = goodEnough.get(0);
            for (LocalSize s : goodEnough) {
                if ((float) s.width / s.height == captureSizeRatio) {
                    previewSize = s;
                    break;
                }
            }

            Collections.reverse(goodEnough);
            videoSize = goodEnough.get(0);
            for (LocalSize s : goodEnough) {
                if ((float) s.width / s.height == captureSizeRatio) {
                    videoSize = s;
                    break;
                }
            }
        }

        return new Pair<>(videoSize, previewSize);
    }

    public static boolean shouldInvertOrientation (int orientation) {
        return orientation % 180 == 90;
    }

    static class LocalSize {

        final int width;
        final int height;

        LocalSize(Camera.Size size) {
            this.width = size.width;
            this.height = size.height;
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        LocalSize(Size size) {
            this.width = size.getWidth();
            this.height = size.getHeight();
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        Size toSize() {
            return new Size(width, height);
        }

    }

    private static class CompareSizesByArea implements Comparator<LocalSize> {
        @Override
        public int compare(LocalSize lhs, LocalSize rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.width * lhs.height - (long) rhs.width * rhs.height);
        }
    }

}
