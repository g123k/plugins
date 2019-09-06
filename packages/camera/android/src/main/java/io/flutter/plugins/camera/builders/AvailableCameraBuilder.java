package io.flutter.plugins.camera.builders;

import java.util.HashMap;

public class AvailableCameraBuilder {

    private String name;
    private int orientation;
    private String type;

    public AvailableCameraBuilder setName(String name) {
        this.name = name;
        return this;
    }

    public AvailableCameraBuilder setOrientation(int orientation) {
        this.orientation = orientation;
        return this;
    }

    public AvailableCameraBuilder setTypeAsFront() {
        this.type = "front";
        return this;
    }

    public AvailableCameraBuilder setTypeAsBack() {
        this.type = "back";
        return this;
    }

    public AvailableCameraBuilder setTypeAsExternal() {
        this.type = "external";
        return this;
    }

    public HashMap<String, Object> build() {
        if (name == null) {
            throw new RuntimeException("The name can not be null!");
        }

        HashMap<String, Object> details = new HashMap<>();

        details.put("name", name);
        details.put("sensorOrientation", orientation);
        details.put("lensFacing", type);

        return details;
    }
}
