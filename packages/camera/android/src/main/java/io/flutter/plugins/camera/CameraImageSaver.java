package io.flutter.plugins.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class CameraImageSaver implements Runnable {

    private final byte[] bytes;
    private final ByteBuffer bytesBuffer;
    private final File file;
    private final EventListener listener;

    public CameraImageSaver(byte[] bytes, File file, EventListener cameraImpl) {
        this.bytes = bytes;
        this.bytesBuffer = null;
        this.file = file;
        this.listener = cameraImpl;
    }

    public CameraImageSaver(ByteBuffer bytesBuffer, File file, EventListener cameraImpl) {
        this.bytes = null;
        this.bytesBuffer = bytesBuffer;
        this.file = file;
        this.listener = cameraImpl;
    }

    @Override
    public void run() {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);

            if (bytes != null) {
                output.write(bytes);
            } else if (bytesBuffer != null) {
                while (0 < bytesBuffer.remaining()) {
                    output.getChannel().write(bytesBuffer);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            listener.onError(e);
        } finally {
            if (output != null) {
                try {
                    output.close();
                    listener.onImageSaved(file);
                } catch (IOException e) {
                    e.printStackTrace();
                    listener.onError(e);
                }
            }
        }
    }


    public interface EventListener {
        void onImageSaved(File file);
        void onError(Exception exception);
    }
}
