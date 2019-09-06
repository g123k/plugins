package io.flutter.plugins.camera.builders;

import java.util.HashMap;

public class CameraOpenResultBuilder {

    private long textureId;
    private int previewWidth;
    private int previewHeight;

    public CameraOpenResultBuilder setTextureId(long textureId) {
        this.textureId = textureId;
        return this;
    }

    public CameraOpenResultBuilder setPreviewWidth(int previewWidth) {
        this.previewWidth = previewWidth;
        return this;
    }

    public CameraOpenResultBuilder setPreviewHeight(int previewHeight) {
        this.previewHeight = previewHeight;
        return this;
    }

    public HashMap<String, Object> build() {
        HashMap<String, Object> details = new HashMap<>();

        details.put("textureId", textureId);
        details.put("previewWidth", previewWidth);
        details.put("previewHeight", previewHeight);

        return details;
    }
}
