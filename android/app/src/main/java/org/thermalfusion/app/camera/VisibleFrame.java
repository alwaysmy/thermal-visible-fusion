package org.thermalfusion.app.camera;

import android.graphics.Bitmap;

/** Copied native-orientation pixels and the exactly timestamp-matched capture metadata.
 * Bitmap pixels are immutable. The listener owns delivered bitmap references; the
 * source never recycles a delivered bitmap. Do not retain an unbounded history.
 */
public final class VisibleFrame {
    public final Bitmap bitmap;
    public final CameraFrameMetadata metadata;

    VisibleFrame(Bitmap bitmap, CameraFrameMetadata metadata) {
        this.bitmap = bitmap;
        this.metadata = metadata;
    }
}
