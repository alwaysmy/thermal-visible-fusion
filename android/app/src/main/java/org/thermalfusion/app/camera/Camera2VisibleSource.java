package org.thermalfusion.app.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import org.thermalfusion.camera.Yuv420Converter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A fixed Camera2 device session producing real copied YUV frames, not a separate
 * Surface/TextureView preview. All listener callbacks are on the main thread.
 *
 * Images and TotalCaptureResults are joined only by identical SENSOR_TIMESTAMP.
 * Missing, duplicate, expired and unmatched data are dropped. Host receipt times
 * are bookkeeping and must not be mislabeled exposure/synchronized IR times.
 *
 * start/stop may be repeated. stop/close invalidate callbacks synchronously, then
 * release Camera2 resources on the worker without waiting on the calling thread.
 * No camera permission is requested here: the activity must obtain it first.
 */
public final class Camera2VisibleSource implements AutoCloseable {
    private static final int MAX_PENDING_IMAGES = 3;
    private static final int MAX_PENDING_RESULTS = 12;
    private static final long MATCH_TIMEOUT_NS = 500_000_000L;
    private static final long MIN_COPY_INTERVAL_NS = 66_666_666L;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Run current;

    public interface Listener {
        void onFrame(VisibleFrame frame);
        void onStatus(String status);
        void onUnavailable(String reason);
    }

    public static final class CameraOption {
        public final String id, label;
        public final boolean logical;
        /** Zero means no supported bounded YUV stream was advertised. */
        public final int previewWidth, previewHeight;
        public final List<String> physicalCameraIds;
        private CameraOption(String id, boolean logical, Size size, List<String> physicalIds) {
            this.id = id;
            this.logical = logical;
            this.previewWidth = size == null ? 0 : size.getWidth();
            this.previewHeight = size == null ? 0 : size.getHeight();
            this.physicalCameraIds = Collections.unmodifiableList(new ArrayList<>(physicalIds));
            label = "Back camera ID " + id + (logical ? " (logical)" : "")
                    + (size == null ? " · no bounded YUV stream" : " · " + previewWidth + "×" + previewHeight);
        }
        @Override public String toString() { return label; }
    }

    public Camera2VisibleSource(Context context) { this.context = context.getApplicationContext(); }

    /** Enumerates only real back-facing IDs advertised by CameraManager. No model/lens guesses.
     * Caller handles CameraAccessException or SecurityException (including permission revocation).
     */
    public static List<CameraOption> listBackCameras(Context context) throws CameraAccessException {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Collections.emptyList();
        List<CameraOption> options = new ArrayList<>();
        String[] ids = manager.getCameraIdList();
        Arrays.sort(ids);
        for (String id : ids) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            boolean logical = isLogical(c);
            List<String> physicalIds = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 28 && logical) physicalIds.addAll(c.getPhysicalCameraIds());
            Collections.sort(physicalIds);
            options.add(new CameraOption(id, logical, selectSize(c), physicalIds));
        }
        return Collections.unmodifiableList(options);
    }

    public synchronized void start(String cameraId, int displayRotationDegrees, Listener listener) {
        if (cameraId == null || listener == null) throw new IllegalArgumentException("Camera ID and listener required");
        if (displayRotationDegrees != 0 && displayRotationDegrees != 90
                && displayRotationDegrees != 180 && displayRotationDegrees != 270) {
            throw new IllegalArgumentException("Display rotation must be 0, 90, 180 or 270 degrees");
        }
        stop();
        Run run = new Run(cameraId, displayRotationDegrees, listener);
        current = run;
        run.thread.start();
        run.worker = new Handler(run.thread.getLooper());
        run.worker.post(run::begin);
    }

    public synchronized void stop() {
        Run old = current;
        current = null;
        if (old != null) old.cancel();
    }

    @Override public void close() { stop(); }

    private static boolean isLogical(CameraCharacteristics c) {
        return Build.VERSION.SDK_INT >= 28 && contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
    }

    private static boolean contains(int[] values, int value) {
        if (values != null) for (int item : values) if (item == value) return true;
        return false;
    }

    private static Size selectSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null) return null;
        Size modest = null, fallback = null;
        for (Size size : sizes) {
            int w = size.getWidth(), h = size.getHeight();
            if (w <= 0 || h <= 0 || w > Yuv420Converter.MAX_DIMENSION || h > Yuv420Converter.MAX_DIMENSION
                    || (long) w * h > Yuv420Converter.MAX_PIXELS) continue;
            if (w <= 640 && h <= 480 && (modest == null || area(size) > area(modest))) modest = size;
            if (fallback == null || area(size) < area(fallback)) fallback = size;
        }
        return modest != null ? modest : fallback;
    }

    private static void closeQuietly(AutoCloseable resource) {
        try { resource.close(); }
        catch (Exception ignored) { /* Best-effort release must continue for every resource. */ }
    }

    private static long area(Size size) { return (long) size.getWidth() * size.getHeight(); }

    private final class Run {
        final String cameraId;
        final int displayRotation;
        final Listener listener;
        final HandlerThread thread = new HandlerThread("visible-camera2");
        final LinkedHashMap<Long, PendingPixels> images = new LinkedHashMap<>();
        final LinkedHashMap<Long, PendingResult> results = new LinkedHashMap<>();
        final ArrayDeque<int[]> freePixels = new ArrayDeque<>();
        // Bounded by the selected logical device's advertised physical IDs, filled once.
        final Map<String, Orientation> physicalOrientations = new LinkedHashMap<>();
        Orientation deviceOrientation;
        final Object deliveryLock = new Object();
        volatile boolean cancelled;
        Handler worker;
        CameraDevice device;
        CameraCaptureSession session;
        ImageReader reader;
        CameraCharacteristics characteristics;
        Size streamSize;
        boolean opening, resourcesReleased, logical;
        boolean videoOff, oisOff, distortionOff, rotateNone;
        long lastCopyReceipt = Long.MIN_VALUE;
        long lastPublishedSensorTimestamp = Long.MIN_VALUE;
        VisibleFrame pendingDelivery;
        boolean deliveryPosted;
        final Runnable deliverLatest = this::deliverLatest;
        final Runnable expirePending = new Runnable() {
            @Override public void run() {
                if (!live()) return;
                evictExpired(SystemClock.elapsedRealtimeNanos());
                worker.postDelayed(this, 250);
            }
        };

        Run(String cameraId, int rotation, Listener listener) {
            this.cameraId = cameraId;
            this.displayRotation = rotation;
            this.listener = listener;
        }

        boolean live() { return !cancelled && current == this; }

        @SuppressLint("MissingPermission") // Checked immediately before open; SecurityException handled below.
        void begin() {
            if (!live()) { release(); return; }
            if (context.checkCallingOrSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail("Camera permission is required for the visible preview");
                return;
            }
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager == null) { fail("Camera service is unavailable"); return; }
                // Verify this exact public ID, not a guessed physical lens alias.
                if (!Arrays.asList(manager.getCameraIdList()).contains(cameraId)) {
                    fail("Selected camera ID is no longer available: " + cameraId); return;
                }
                characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    fail("Selected camera is not an advertised back-facing device"); return;
                }
                logical = isLogical(characteristics);
                deviceOrientation = new Orientation(cameraId, characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
                if (Build.VERSION.SDK_INT >= 29 && logical) {
                    for (String physicalId : characteristics.getPhysicalCameraIds()) {
                        try {
                            CameraCharacteristics physical = manager.getCameraCharacteristics(physicalId);
                            physicalOrientations.put(physicalId,
                                    new Orientation(physicalId, physical.get(CameraCharacteristics.SENSOR_ORIENTATION)));
                        } catch (CameraAccessException | IllegalArgumentException unavailable) {
                            // This declared physical device cannot be characterized. Do not guess its orientation.
                            physicalOrientations.put(physicalId, new Orientation(null, null));
                        }
                    }
                }
                streamSize = selectSize(characteristics);
                if (streamSize == null) { fail("Camera " + cameraId + " has no supported bounded YUV preview size"); return; }
                reader = ImageReader.newInstance(streamSize.getWidth(), streamSize.getHeight(), ImageFormat.YUV_420_888, 3);
                reader.setOnImageAvailableListener(this::copyLatest, worker);
                status("Opening back camera ID " + cameraId + " · " + streamSize);
                opening = true;
                manager.openCamera(cameraId, deviceState, worker);
            } catch (CameraAccessException | RuntimeException error) {
                opening = false;
                fail("Unable to open camera " + cameraId + ": " + error.getClass().getSimpleName());
            }
        }

        @SuppressWarnings("deprecation") // API 21-compatible session creation.
        final CameraDevice.StateCallback deviceState = new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) {
                opening = false;
                if (!live()) { closeQuietly(camera); release(); return; }
                device = camera;
                try {
                    camera.createCaptureSession(Collections.singletonList(reader.getSurface()), sessionState, worker);
                } catch (CameraAccessException | RuntimeException error) {
                    fail("Camera session creation failed: " + error.getClass().getSimpleName());
                }
            }
            @Override public void onDisconnected(CameraDevice camera) {
                opening = false;
                closeQuietly(camera);
                fail("Camera " + cameraId + " disconnected");
            }
            @Override public void onError(CameraDevice camera, int error) {
                opening = false;
                closeQuietly(camera);
                fail("Camera " + cameraId + " error " + error);
            }
        };

        final CameraCaptureSession.StateCallback sessionState = new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession configured) {
                if (!live()) { closeQuietly(configured); release(); return; }
                session = configured;
                try {
                    CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    request.addTarget(reader.getSurface());
                    configureRequest(request);
                    configured.setRepeatingRequest(request.build(), captures, worker);
                    status("Visible camera ID " + cameraId + " running; awaiting exact image/result timestamp match");
                    worker.post(expirePending);
                } catch (CameraAccessException | RuntimeException error) {
                    fail("Camera capture failed: " + error.getClass().getSimpleName());
                }
            }
            @Override public void onConfigureFailed(CameraCaptureSession failed) {
                closeQuietly(failed);
                fail("Camera " + cameraId + " rejected its YUV preview session");
            }
        };

        void configureRequest(CaptureRequest.Builder request) {
            List<CaptureRequest.Key<?>> keys = characteristics.getAvailableCaptureRequestKeys();
            if (keys == null) keys = Collections.emptyList();
            if (keys.contains(CaptureRequest.CONTROL_AF_MODE)
                    && contains(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            videoOff = keys.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
                    && contains(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            if (videoOff) request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            oisOff = keys.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                    && contains(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            if (oisOff) request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            if (Build.VERSION.SDK_INT >= 28) {
                distortionOff = keys.contains(CaptureRequest.DISTORTION_CORRECTION_MODE)
                        && contains(characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES), CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
                if (distortionOff) request.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                rotateNone = keys.contains(CaptureRequest.SCALER_ROTATE_AND_CROP)
                        && contains(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES), CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
                if (rotateNone) request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
            }
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> selected = null;
            if (ranges != null && keys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)) {
                for (Range<Integer> range : ranges) {
                    if (range.getLower() > 0 && range.getLower() <= 15 && range.getUpper() >= 15 && range.getUpper() <= 30
                            && (selected == null || range.getUpper() < selected.getUpper()
                            || (range.getUpper().equals(selected.getUpper()) && range.getLower() > selected.getLower()))) selected = range;
                }
                if (selected != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected);
            }
        }

        final CameraCaptureSession.CaptureCallback captures = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                if (!live()) return;
                try {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp == null || timestamp <= 0 || timestamp <= lastPublishedSensorTimestamp) return;
                    long now = SystemClock.elapsedRealtimeNanos();
                    evictExpired(now);
                    PendingPixels pixels = images.remove(timestamp);
                    if (pixels != null) publish(pixels, result);
                    else {
                        results.put(timestamp, new PendingResult(result, now));
                        while (results.size() > MAX_PENDING_RESULTS) results.remove(results.keySet().iterator().next());
                    }
                } catch (RuntimeException error) {
                    fail("Visible capture metadata failed: " + error.getClass().getSimpleName());
                }
            }
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                if (!live()) return;
                // A failure carries no trustworthy image timestamp. Never attach a prior result.
                clearPending();
            }
        };

        void copyLatest(ImageReader source) {
            Image image = null;
            int[] pixels = null;
            try {
                if (!live()) return;
                image = source.acquireLatestImage();
                if (image == null) return;
                long receipt = SystemClock.elapsedRealtimeNanos();
                evictExpired(receipt);
                long timestamp = image.getTimestamp();
                if (timestamp <= 0 || timestamp <= lastPublishedSensorTimestamp
                        || (lastCopyReceipt != Long.MIN_VALUE && receipt - lastCopyReceipt < MIN_COPY_INTERVAL_NS)) return;
                if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length != 3) {
                    fail("Camera returned an unexpected image format"); return;
                }
                while (images.size() >= MAX_PENDING_IMAGES) removeOldestImage();
                Rect crop = new Rect(image.getCropRect());
                int count = streamSize.getWidth() * streamSize.getHeight();
                pixels = freePixels.pollFirst();
                if (pixels == null) pixels = new int[count];
                Image.Plane[] planes = image.getPlanes();
                Yuv420Converter.copyToArgb(image.getWidth(), image.getHeight(), crop.left, crop.top, crop.right, crop.bottom,
                        planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                        planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                        planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(), pixels);
                PendingPixels copy = new PendingPixels(timestamp, receipt, image.getWidth(), image.getHeight(), crop, pixels);
                pixels = null;
                lastCopyReceipt = receipt;
                // Native planes must not escape their Image lifetime; all reads finished above.
                image.close();
                image = null;
                PendingResult result = results.remove(timestamp);
                if (result != null) publish(copy, result.result);
                else {
                    PendingPixels previous = images.put(timestamp, copy);
                    if (previous != null) recyclePixels(previous.pixels);
                }
            } catch (RuntimeException error) {
                if (live()) fail("Visible frame copy failed: " + error.getClass().getSimpleName());
            } finally {
                if (image != null) image.close();
                if (pixels != null) recyclePixels(pixels);
            }
        }

        void publish(PendingPixels image, TotalCaptureResult result) {
            try {
                if (!live() || image.timestamp <= lastPublishedSensorTimestamp) return;
                Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (timestamp == null || timestamp != image.timestamp) return;
                // Rotate/crop affects YUV pixels AND field of view. A rotation subtraction
                // would not restore the missing geometry, so reject instead of double-rotating.
                if (Build.VERSION.SDK_INT >= 31) {
                    Integer mode = result.get(CaptureResult.SCALER_ROTATE_AND_CROP);
                    if (mode != null && mode != CaptureResult.SCALER_ROTATE_AND_CROP_NONE) {
                        fail("Camera applied unsupported rotate/crop mode " + mode + "; visible preview stopped");
                        return;
                    }
                }
                Orientation orientation = orientationFor(result);
                CameraFrameMetadata metadata = new CameraFrameMetadata(cameraId, logical, characteristics, result,
                        image.width, image.height, image.crop, displayRotation, image.timestamp, image.receipt,
                        orientation.degrees, orientation.cameraId, videoOff, oisOff, distortionOff, rotateNone);
                Bitmap bitmap = Bitmap.createBitmap(image.pixels, image.crop.width(), image.crop.height(), Bitmap.Config.ARGB_8888);
                lastPublishedSensorTimestamp = image.timestamp;
                queueDelivery(new VisibleFrame(bitmap, metadata));
            } finally {
                recyclePixels(image.pixels);
            }
        }

        Orientation orientationFor(TotalCaptureResult result) {
            if (logical && Build.VERSION.SDK_INT >= 29) {
                String activeId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                if (activeId != null) {
                    Orientation physical = physicalOrientations.get(activeId);
                    return physical == null ? Orientation.UNKNOWN : physical;
                }
                // API32+ logical orientation can vary with folding state. A session-time
                // characteristic cannot establish this frame's orientation without its physical ID.
                // https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_ORIENTATION
                if (Build.VERSION.SDK_INT >= 32) return Orientation.UNKNOWN;
            }
            return deviceOrientation;
        }

        void evictExpired(long now) {
            Iterator<PendingPixels> iterator = images.values().iterator();
            while (iterator.hasNext()) {
                PendingPixels p = iterator.next();
                if (now - p.receipt > MATCH_TIMEOUT_NS || p.timestamp <= lastPublishedSensorTimestamp) {
                    recyclePixels(p.pixels);
                    iterator.remove();
                }
            }
            Iterator<Map.Entry<Long, PendingResult>> resultIterator = results.entrySet().iterator();
            while (resultIterator.hasNext()) {
                Map.Entry<Long, PendingResult> entry = resultIterator.next();
                if (now - entry.getValue().receipt > MATCH_TIMEOUT_NS || entry.getKey() <= lastPublishedSensorTimestamp) resultIterator.remove();
            }
        }

        void removeOldestImage() {
            Iterator<PendingPixels> iterator = images.values().iterator();
            PendingPixels old = iterator.next();
            iterator.remove();
            recyclePixels(old.pixels);
        }

        void recyclePixels(int[] pixels) {
            if (!resourcesReleased && freePixels.size() < MAX_PENDING_IMAGES + 1) freePixels.offerFirst(pixels);
        }

        void clearPending() {
            for (PendingPixels p : images.values()) recyclePixels(p.pixels);
            images.clear();
            results.clear();
        }

        void queueDelivery(VisibleFrame frame) {
            synchronized (deliveryLock) {
                if (!live()) { frame.bitmap.recycle(); return; }
                if (pendingDelivery != null) pendingDelivery.bitmap.recycle();
                pendingDelivery = frame;
                if (!deliveryPosted) {
                    deliveryPosted = true;
                    main.post(deliverLatest);
                }
            }
        }

        void deliverLatest() {
            VisibleFrame frame;
            synchronized (deliveryLock) {
                frame = pendingDelivery;
                pendingDelivery = null;
                deliveryPosted = false;
            }
            if (frame == null) return;
            if (live() && SystemClock.elapsedRealtimeNanos() - frame.metadata.hostReceiptElapsedRealtimeNanos <= MATCH_TIMEOUT_NS) {
                listener.onFrame(frame);
            } else frame.bitmap.recycle();
        }

        void clearDelivery() {
            synchronized (deliveryLock) {
                main.removeCallbacks(deliverLatest);
                if (pendingDelivery != null) pendingDelivery.bitmap.recycle();
                pendingDelivery = null;
                deliveryPosted = false;
            }
        }

        void status(String text) { main.post(() -> { if (live()) listener.onStatus(text); }); }

        void fail(String reason) {
            boolean notify = live();
            cancelled = true;
            clearDelivery();
            release();
            // Keep the identity check, but permit this terminal callback after cancellation.
            if (notify) main.post(() -> { if (current == this) listener.onUnavailable(reason); });
        }

        void cancel() {
            cancelled = true;
            clearDelivery();
            if (worker != null) worker.post(this::release);
        }

        void release() {
            // This method always runs on the camera worker. Keep it alive only if an
            // outstanding open needs its eventual onOpened/onError callback closed.
            if (!resourcesReleased) {
                resourcesReleased = true;
                worker.removeCallbacks(expirePending);
                clearPending();
                freePixels.clear();
                physicalOrientations.clear();
                if (session != null) { closeQuietly(session); session = null; }
                if (device != null) { closeQuietly(device); device = null; }
                if (reader != null) {
                    try { reader.setOnImageAvailableListener(null, null); }
                    catch (RuntimeException ignored) { /* Continue closing after a service failure. */ }
                    closeQuietly(reader);
                    reader = null;
                }
            }
            if (!opening) thread.quitSafely();
        }
    }

    private static final class Orientation {
        static final Orientation UNKNOWN = new Orientation(null, null);
        final String cameraId;
        final Integer degrees;
        Orientation(String cameraId, Integer degrees) {
            boolean valid = degrees != null && degrees >= 0 && degrees <= 270 && degrees % 90 == 0;
            this.cameraId = valid ? cameraId : null;
            this.degrees = valid ? degrees : null;
        }
    }

    private static final class PendingPixels {
        final long timestamp, receipt;
        final int width, height;
        final Rect crop;
        final int[] pixels;
        PendingPixels(long timestamp, long receipt, int width, int height, Rect crop, int[] pixels) {
            this.timestamp = timestamp;
            this.receipt = receipt;
            this.width = width;
            this.height = height;
            this.crop = crop;
            this.pixels = pixels;
        }
    }

    private static final class PendingResult {
        final TotalCaptureResult result;
        final long receipt;
        PendingResult(TotalCaptureResult result, long receipt) { this.result = result; this.receipt = receipt; }
    }
}
