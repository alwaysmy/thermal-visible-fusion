package org.thermalfusion.app.camera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;

/** Immutable metadata for ONE copied Image and its exact TotalCaptureResult.
 * A null result value means unknown, never the requested/default value.
 * Sensor exposure time is distinct from host callback receipt time. REALTIME
 * identifies the sensor clock's timebase, not synchronization with the IR USB stream.
 */
public final class CameraFrameMetadata {
    public enum TimestampSource { REALTIME, UNKNOWN }

    public static final class Bounds {
        public final int left, top, right, bottom;
        Bounds(Rect rect) { left = rect.left; top = rect.top; right = rect.right; bottom = rect.bottom; }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        public int[] toArray() { return new int[] { left, top, right, bottom }; }
        @Override public String toString() { return left + "," + top + "–" + right + "," + bottom; }
    }

    public final String cameraId;
    public final boolean logicalCamera;
    /** Null means the device did not report it; cameraId is NOT a guessed physical ID. */
    public final String activePhysicalId;
    /** Native Image dimensions before its Image.cropRect is copied. */
    public final int imageWidth, imageHeight;
    /** Copied bitmap dimensions, before presentation rotation. */
    public final int bufferWidth, bufferHeight;
    public final Bounds imageCrop, activeArray, preCorrectionActiveArray, cropRegion;
    /** Optional per-frame physical-sensor crop (API 35+), separate from logical cropRegion. */
    public final Bounds activePhysicalSensorCropRegion;
    public final Integer sensorOrientationDegrees;
    /** ID supplying the orientation: actual active physical ID, fixed device ID, or null. */
    public final String sensorOrientationCameraId;
    public final int displayRotationDegrees;
    /** Null when frame sensor orientation or (on API 31+) achieved ISP rotate/crop is unknown.
     * Known non-NONE rotate/crop frames are rejected by the source before delivery.
     */
    public final Integer bufferToUprightRotationDegrees;
    public final boolean mirrored = false;
    public final long sensorTimestampNanos;
    public final long hostReceiptElapsedRealtimeNanos;
    public final long captureFrameNumber;
    public final TimestampSource timestampSource;
    public final Float focusDistanceDiopters, focalLengthMillimeters, zoomRatio;
    public final Integer autofocusMode, autofocusState, lensState;
    public final Integer videoStabilizationMode, opticalStabilizationMode, distortionCorrectionMode;
    public final Integer rotateAndCropMode;
    public final Long exposureTimeNanos, rollingShutterSkewNanos;
    public final boolean requestedVideoStabilizationOff, requestedOpticalStabilizationOff;
    public final boolean requestedDistortionCorrectionOff, requestedRotateAndCropNone;

    CameraFrameMetadata(String cameraId, boolean logicalCamera, CameraCharacteristics characteristics,
            TotalCaptureResult result, int imageWidth, int imageHeight, Rect imageCrop,
            int displayRotationDegrees, long timestamp, long hostReceipt,
            Integer frameSensorOrientation, String orientationCameraId,
            boolean videoOff, boolean oisOff, boolean distortionOff, boolean rotateNone) {
        this.cameraId = cameraId;
        this.logicalCamera = logicalCamera;
        activePhysicalId = Build.VERSION.SDK_INT >= 29
                ? result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) : null;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.imageCrop = bounds(imageCrop);
        bufferWidth = imageCrop.width();
        bufferHeight = imageCrop.height();
        activeArray = bounds(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
        preCorrectionActiveArray = Build.VERSION.SDK_INT >= 23
                ? bounds(characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)) : null;
        cropRegion = bounds(result.get(CaptureResult.SCALER_CROP_REGION));
        activePhysicalSensorCropRegion = Build.VERSION.SDK_INT >= 35
                ? bounds(result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION)) : null;
        sensorOrientationDegrees = frameSensorOrientation;
        sensorOrientationCameraId = orientationCameraId;
        this.displayRotationDegrees = displayRotationDegrees;
        rotateAndCropMode = Build.VERSION.SDK_INT >= 31
                ? result.get(CaptureResult.SCALER_ROTATE_AND_CROP) : null;
        boolean knownUnrotated = Build.VERSION.SDK_INT < 31
                || (rotateAndCropMode != null && rotateAndCropMode == CaptureResult.SCALER_ROTATE_AND_CROP_NONE);
        bufferToUprightRotationDegrees = sensorOrientationDegrees == null || !knownUnrotated ? null
                : (sensorOrientationDegrees - displayRotationDegrees + 360) % 360;
        sensorTimestampNanos = timestamp;
        hostReceiptElapsedRealtimeNanos = hostReceipt;
        captureFrameNumber = result.getFrameNumber();
        Integer source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        timestampSource = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                ? TimestampSource.REALTIME : TimestampSource.UNKNOWN;
        focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        focalLengthMillimeters = result.get(CaptureResult.LENS_FOCAL_LENGTH);
        autofocusMode = result.get(CaptureResult.CONTROL_AF_MODE);
        autofocusState = result.get(CaptureResult.CONTROL_AF_STATE);
        lensState = result.get(CaptureResult.LENS_STATE);
        videoStabilizationMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
        opticalStabilizationMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        distortionCorrectionMode = Build.VERSION.SDK_INT >= 28
                ? result.get(CaptureResult.DISTORTION_CORRECTION_MODE) : null;
        zoomRatio = Build.VERSION.SDK_INT >= 30 ? result.get(CaptureResult.CONTROL_ZOOM_RATIO) : null;
        exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        rollingShutterSkewNanos = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        requestedVideoStabilizationOff = videoOff;
        requestedOpticalStabilizationOff = oisOff;
        requestedDistortionCorrectionOff = distortionOff;
        requestedRotateAndCropNone = rotateNone;
    }

    private static Bounds bounds(Rect rect) { return rect == null ? null : new Bounds(rect); }
}
