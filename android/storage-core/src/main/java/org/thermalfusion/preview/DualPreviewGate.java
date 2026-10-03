package org.thermalfusion.preview;

/**
 * Bounded lifecycle and host-receipt diagnostics for independent preview panes.
 *
 * <p>This stores only the latest displayed metadata per stream, never pixels or
 * temperatures. All host times must use Android elapsedRealtimeNanos in the same
 * boot. Camera sensor timestamps are separate metadata; the IR API deliberately
 * has no exposure timestamp. Similar receipt times never establish synchronization.
 *
 * <p>Call start for a foreground run, then begin each available stream. Keep its
 * returned token in every queued callback. Serialize painting and gate calls on
 * the UI executor: check isVisibleCurrent/isInfraredCurrent before painting and
 * acknowledge only successfully displayed frames. If acknowledgement returns
 * false, discard/clear that candidate pane. Clear the matching gate and actual
 * pane on permission denial, source errors, disconnect, and stream stop. Stop,
 * background, and close clear both gates; callers must also clear both UI panes.
 * Re-evaluate snapshot periodically while an old image remains on screen.
 */
public final class DualPreviewGate {
    public enum CameraTimestampSource {
        /** Camera sensor timestamp has an unknown/unrelated timebase. */
        UNKNOWN,
        /** Camera2 reports SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME. */
        REALTIME
    }

    public static final class Snapshot {
        public final boolean running;
        public final boolean closed;
        public final boolean visibleFresh;
        public final boolean infraredFresh;
        public final Long visibleReceiptTimeNs;
        public final Long infraredReceiptTimeNs;
        /** Absolute host-receipt delta; null unless both displayed frames are fresh. */
        public final Long receiptDeltaNs;
        public final Long sensorTimestampNs;
        public final CameraTimestampSource sensorTimestampSource;
        public final CameraGeometry visibleGeometry;
        /** Intentionally false in this preview-only milestone; no fusion policy is implemented. */
        public final boolean fusionAllowed = false;
        public final boolean exposureSynchronized = false;
        public final boolean calibrationReady = false;

        private Snapshot(boolean running, boolean closed, boolean visibleFresh,
                boolean infraredFresh, Long visibleReceiptTimeNs, Long infraredReceiptTimeNs,
                Long receiptDeltaNs, Long sensorTimestampNs,
                CameraTimestampSource sensorTimestampSource, CameraGeometry visibleGeometry) {
            this.running = running;
            this.closed = closed;
            this.visibleFresh = visibleFresh;
            this.infraredFresh = infraredFresh;
            this.visibleReceiptTimeNs = visibleReceiptTimeNs;
            this.infraredReceiptTimeNs = infraredReceiptTimeNs;
            this.receiptDeltaNs = receiptDeltaNs;
            this.sensorTimestampNs = sensorTimestampNs;
            this.sensorTimestampSource = sensorTimestampSource;
            this.visibleGeometry = visibleGeometry;
        }
    }

    private final long staleAfterNs;
    private boolean running;
    private boolean closed;
    private long nextToken;
    private long visibleToken;
    private long infraredToken;
    private String expectedCameraId;
    private int expectedDisplayRotationDegrees;
    private Long visibleReceiptTimeNs;
    private Long infraredReceiptTimeNs;
    private Long sensorTimestampNs;
    private CameraTimestampSource sensorTimestampSource = CameraTimestampSource.UNKNOWN;
    private CameraGeometry visibleGeometry;

    public DualPreviewGate(long staleAfterNs) {
        if (staleAfterNs <= 0) throw new IllegalArgumentException("Expiry must be positive");
        this.staleAfterNs = staleAfterNs;
    }

    /** Starts/restarts a foreground run, invalidating all previous tokens and frames. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Preview gate is closed");
        clearVisible();
        clearInfrared();
        running = true;
    }

    /** Binds a new camera/rotation session without disturbing pure thermal preview. */
    public synchronized long beginVisible(String cameraId, int displayRotationDegrees) {
        requireRunning();
        CameraGeometry.validateCameraId(cameraId);
        CameraGeometry.validateRotation(displayRotationDegrees);
        clearVisible();
        visibleToken = allocateToken();
        expectedCameraId = cameraId;
        expectedDisplayRotationDegrees = displayRotationDegrees;
        return visibleToken;
    }

    /** Binds a new IR session without disturbing the visible pane. */
    public synchronized long beginInfrared() {
        requireRunning();
        clearInfrared();
        infraredToken = allocateToken();
        return infraredToken;
    }

    public synchronized boolean isVisibleCurrent(long token) {
        return running && !closed && token != 0 && token == visibleToken;
    }

    public synchronized boolean isInfraredCurrent(long token) {
        return running && !closed && token != 0 && token == infraredToken;
    }

    /**
     * Acknowledges the exact successfully displayed camera frame, not just an
     * arriving callback. Geometry may change per frame (focus, crop, modes).
     * Negative sensor metadata is invalid; REALTIME sensor timestamps after host
     * receipt are also invalid. UNKNOWN sensor timestamps are never compared
     * with host times because their clock domain is not established.
     */
    public synchronized boolean visibleDisplayed(long token, long hostReceiptNs,
            long nowElapsedRealtimeNs, CameraGeometry geometry, Long cameraSensorTimestampNs,
            CameraTimestampSource timestampSource) {
        if (!isVisibleCurrent(token) || geometry == null
                || !expectedCameraId.equals(geometry.cameraId)
                || geometry.displayRotationDegrees == null
                || geometry.displayRotationDegrees != expectedDisplayRotationDegrees
                || !acceptableReceipt(hostReceiptNs, nowElapsedRealtimeNs, visibleReceiptTimeNs)) {
            return false;
        }
        CameraTimestampSource source = timestampSource == null
                ? CameraTimestampSource.UNKNOWN : timestampSource;
        if (cameraSensorTimestampNs != null && (cameraSensorTimestampNs < 0
                || (source == CameraTimestampSource.REALTIME
                    && cameraSensorTimestampNs > hostReceiptNs))) {
            return false;
        }
        visibleReceiptTimeNs = hostReceiptNs;
        visibleGeometry = geometry;
        sensorTimestampNs = cameraSensorTimestampNs;
        sensorTimestampSource = source;
        return true;
    }

    /** IR time is strictly host receipt; no IR exposure time is known here. */
    public synchronized boolean infraredDisplayed(long token, long hostReceiptNs,
            long nowElapsedRealtimeNs) {
        if (!isInfraredCurrent(token)
                || !acceptableReceipt(hostReceiptNs, nowElapsedRealtimeNs, infraredReceiptTimeNs)) {
            return false;
        }
        infraredReceiptTimeNs = hostReceiptNs;
        return true;
    }

    /** Permission denial, camera error, rotation/camera change, or camera stop. */
    public synchronized void clearVisible() {
        visibleToken = 0;
        expectedCameraId = null;
        visibleReceiptTimeNs = null;
        visibleGeometry = null;
        sensorTimestampNs = null;
        sensorTimestampSource = CameraTimestampSource.UNKNOWN;
    }

    /** USB denial, disconnect, source error, or IR stop. */
    public synchronized void clearInfrared() {
        infraredToken = 0;
        infraredReceiptTimeNs = null;
    }

    public synchronized void stop() {
        running = false;
        clearVisible();
        clearInfrared();
    }

    public synchronized void background() { stop(); }

    /** Terminal close; a new gate is needed for a new owner/activity. */
    public synchronized void close() {
        stop();
        closed = true;
    }

    public synchronized Snapshot snapshot(long nowElapsedRealtimeNs) {
        boolean visibleFresh = running && visibleReceiptTimeNs != null
                && FrameFreshness.isFresh(visibleReceiptTimeNs, nowElapsedRealtimeNs, staleAfterNs);
        boolean infraredFresh = running && infraredReceiptTimeNs != null
                && FrameFreshness.isFresh(infraredReceiptTimeNs, nowElapsedRealtimeNs, staleAfterNs);
        Long delta = visibleFresh && infraredFresh
                ? Math.abs(visibleReceiptTimeNs - infraredReceiptTimeNs) : null;
        // Both receipts are nonnegative, so their difference and abs cannot overflow.
        return new Snapshot(running, closed, visibleFresh, infraredFresh,
                visibleReceiptTimeNs, infraredReceiptTimeNs, delta, sensorTimestampNs,
                sensorTimestampSource, visibleGeometry);
    }

    private boolean acceptableReceipt(long receipt, long now, Long previous) {
        return FrameFreshness.isFresh(receipt, now, staleAfterNs)
                && (previous == null || receipt > previous);
    }

    private void requireRunning() {
        if (!running || closed) throw new IllegalStateException("Preview gate is not running");
    }

    private long allocateToken() {
        if (nextToken == Long.MAX_VALUE) {
            close();
            throw new IllegalStateException("Preview session tokens exhausted");
        }
        return ++nextToken;
    }
}
