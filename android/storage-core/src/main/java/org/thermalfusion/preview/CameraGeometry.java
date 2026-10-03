package org.thermalfusion.preview;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable metadata for one displayed visible-camera frame, not a calibration.
 *
 * <p>Rectangles use sensor pixel coordinates, {@code [left, top, right, bottom]},
 * with exclusive right/bottom. Unknown optional values remain null. Buffer size
 * describes the image buffer, not the size or transform of its on-screen view.
 * No intrinsics, lens model, mounting pose, exposure synchronization, or thermal
 * correspondence is implied by these fields, even when every field is known.
 */
public final class CameraGeometry {
    public static final int MAX_BUFFER_DIMENSION = 16384;
    public static final long MAX_BUFFER_PIXELS = 67_108_864L;
    public static final int MAX_CAMERA_ID_LENGTH = 256;

    public final String cameraId;
    public final int bufferWidth;
    public final int bufferHeight;
    public final Integer sensorOrientationDegrees;
    public final Integer displayRotationDegrees;
    public final Float focusDistanceDiopters;
    public final Integer videoStabilizationMode;
    public final Integer opticalStabilizationMode;
    public final Integer distortionCorrectionMode;
    private final int[] activeArray;
    private final int[] cropRegion;

    public CameraGeometry(String cameraId, int bufferWidth, int bufferHeight,
            int[] activeArray, int[] cropRegion, Integer sensorOrientationDegrees,
            Integer displayRotationDegrees, Float focusDistanceDiopters,
            Integer videoStabilizationMode, Integer opticalStabilizationMode,
            Integer distortionCorrectionMode) {
        validateCameraId(cameraId);
        if (bufferWidth <= 0 || bufferHeight <= 0
                || bufferWidth > MAX_BUFFER_DIMENSION || bufferHeight > MAX_BUFFER_DIMENSION
                || (long) bufferWidth * bufferHeight > MAX_BUFFER_PIXELS) {
            throw new IllegalArgumentException("Invalid or excessive camera buffer dimensions");
        }
        validateRotation(sensorOrientationDegrees);
        validateRotation(displayRotationDegrees);
        if (focusDistanceDiopters != null && (Float.isNaN(focusDistanceDiopters)
                || Float.isInfinite(focusDistanceDiopters)
                || focusDistanceDiopters < 0)) {
            throw new IllegalArgumentException("Focus distance must be finite and nonnegative");
        }
        validateMode(videoStabilizationMode);
        validateMode(opticalStabilizationMode);
        validateMode(distortionCorrectionMode);
        this.activeArray = copyRectangle(activeArray);
        this.cropRegion = copyRectangle(cropRegion);
        if (this.activeArray != null && this.cropRegion != null
                && (this.cropRegion[0] < this.activeArray[0]
                    || this.cropRegion[1] < this.activeArray[1]
                    || this.cropRegion[2] > this.activeArray[2]
                    || this.cropRegion[3] > this.activeArray[3])) {
            throw new IllegalArgumentException("Crop region lies outside active array");
        }
        this.cameraId = cameraId;
        this.bufferWidth = bufferWidth;
        this.bufferHeight = bufferHeight;
        this.sensorOrientationDegrees = sensorOrientationDegrees;
        this.displayRotationDegrees = displayRotationDegrees;
        this.focusDistanceDiopters = focusDistanceDiopters;
        this.videoStabilizationMode = videoStabilizationMode;
        this.opticalStabilizationMode = opticalStabilizationMode;
        this.distortionCorrectionMode = distortionCorrectionMode;
    }

    /** Returns a defensive copy, or null when unknown. */
    public int[] activeArray() { return activeArray == null ? null : activeArray.clone(); }

    /** Returns a defensive copy, or null when unknown. */
    public int[] cropRegion() { return cropRegion == null ? null : cropRegion.clone(); }

    /** Field completeness only; this does not establish calibration suitability. */
    public boolean hasCompleteMetadata() {
        return activeArray != null && cropRegion != null && sensorOrientationDegrees != null
                && displayRotationDegrees != null && focusDistanceDiopters != null
                && videoStabilizationMode != null && opticalStabilizationMode != null
                && distortionCorrectionMode != null;
    }

    /** No verified calibration is implemented in this preview-only prototype. */
    public boolean calibrationReady() { return false; }

    static void validateCameraId(String cameraId) {
        if (cameraId == null || cameraId.trim().isEmpty()
                || cameraId.length() > MAX_CAMERA_ID_LENGTH) {
            throw new IllegalArgumentException("Camera ID must be present and bounded");
        }
    }

    static void validateRotation(Integer degrees) {
        if (degrees != null && (degrees < 0 || degrees > 270 || degrees % 90 != 0)) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, 270 degrees or unknown");
        }
    }

    private static void validateMode(Integer mode) {
        if (mode != null && mode < 0) {
            throw new IllegalArgumentException("Camera mode must be nonnegative or unknown");
        }
    }

    private static int[] copyRectangle(int[] rectangle) {
        if (rectangle == null) return null;
        if (rectangle.length != 4 || rectangle[0] < 0 || rectangle[1] < 0
                || rectangle[2] <= rectangle[0] || rectangle[3] <= rectangle[1]) {
            throw new IllegalArgumentException("Invalid sensor rectangle");
        }
        return rectangle.clone();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CameraGeometry)) return false;
        CameraGeometry value = (CameraGeometry) other;
        return cameraId.equals(value.cameraId) && bufferWidth == value.bufferWidth
                && bufferHeight == value.bufferHeight
                && Arrays.equals(activeArray, value.activeArray)
                && Arrays.equals(cropRegion, value.cropRegion)
                && Objects.equals(sensorOrientationDegrees, value.sensorOrientationDegrees)
                && Objects.equals(displayRotationDegrees, value.displayRotationDegrees)
                && Objects.equals(focusDistanceDiopters, value.focusDistanceDiopters)
                && Objects.equals(videoStabilizationMode, value.videoStabilizationMode)
                && Objects.equals(opticalStabilizationMode, value.opticalStabilizationMode)
                && Objects.equals(distortionCorrectionMode, value.distortionCorrectionMode);
    }

    @Override public int hashCode() {
        int result = Objects.hash(cameraId, bufferWidth, bufferHeight, sensorOrientationDegrees,
                displayRotationDegrees, focusDistanceDiopters, videoStabilizationMode,
                opticalStabilizationMode, distortionCorrectionMode);
        result = 31 * result + Arrays.hashCode(activeArray);
        return 31 * result + Arrays.hashCode(cropRegion);
    }
}
