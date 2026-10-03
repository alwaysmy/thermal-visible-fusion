package org.thermalfusion.app.sdk;

/**
 * SDK-neutral integration seam. There is deliberately no vendor implementation in this app.
 * Implement only after verifying licensing, USB permissions, callback ownership and format.
 */
public interface ThermalSource {
    interface Listener {
        void onFrame(ThermalFrame frame);
        void onUnavailable(String reason);
    }
    void start(Listener listener);
    void stop();

    /** Copied buffers, never a vendor callback's recycled memory. Null means unavailable. */
    final class ThermalFrame {
        public final int width;
        public final int height;
        public final long sequence;
        public final Long captureTimeNs;
        public final String clockId;
        public final long hostReceiptTimeNs;
        public final String formatAndGeometryFingerprint;
        public final boolean valid;
        public final boolean nucInProgress;
        private final short[] rawU16;
        private final float[] temperatureC;

        public ThermalFrame(int width, int height, long sequence, Long captureTimeNs,
                String clockId, long hostReceiptTimeNs, String fingerprint, boolean valid,
                boolean nucInProgress, short[] rawU16, float[] temperatureC) {
            long pixels = (long) width * height;
            if (width <= 0 || height <= 0 || pixels > Integer.MAX_VALUE
                    || (rawU16 != null && rawU16.length != pixels)
                    || (temperatureC != null && temperatureC.length != pixels)) {
                throw new IllegalArgumentException("Frame geometry/buffer mismatch");
            }
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.captureTimeNs = captureTimeNs;
            this.clockId = clockId;
            this.hostReceiptTimeNs = hostReceiptTimeNs;
            this.formatAndGeometryFingerprint = fingerprint;
            this.valid = valid;
            this.nucInProgress = nucInProgress;
            this.rawU16 = rawU16 == null ? null : rawU16.clone();
            this.temperatureC = temperatureC == null ? null : temperatureC.clone();
        }
        public short[] copyRawU16() { return rawU16 == null ? null : rawU16.clone(); }
        public float[] copyTemperatureC() { return temperatureC == null ? null : temperatureC.clone(); }
    }
}
