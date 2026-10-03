package org.thermalfusion.preview;

/** Stateless expiry policy for preview frames using one host monotonic clock. */
public final class FrameFreshness {
    private FrameFreshness() { }

    /**
     * Checks whether a received frame is younger than its configured expiry.
     *
     * <p>On Android, both timestamps must come from
     * {@code SystemClock.elapsedRealtimeNanos()} in the current device boot.
     * Capture the receipt timestamp as the frame enters the host callback,
     * before conversion or queueing. Do not substitute wall-clock time,
     * renderer time, a vendor/device timestamp, or a previous-boot value.
     *
     * <p>A frame is stale when its age is equal to or greater than
     * {@code staleAfterNs}. Negative timestamps or a receipt in the future
     * are invalid and return false. Zero is a valid monotonic timestamp; the
     * caller must track whether it has received a frame separately.
     *
     * <p>This only measures time since host receipt. It cannot prove sensor
     * exposure time, source latency, or synchronization with another camera.
     * The caller must re-evaluate at use time and periodically while showing
     * a frame, and invalidate frames on disconnect/stop independently.
     *
     * @throws IllegalArgumentException if {@code staleAfterNs <= 0}
     */
    public static boolean isFresh(long hostReceiptTimeNs, long nowElapsedRealtimeNs,
            long staleAfterNs) {
        if (staleAfterNs <= 0) {
            throw new IllegalArgumentException("Frame expiry must be positive");
        }
        if (hostReceiptTimeNs < 0 || nowElapsedRealtimeNs < hostReceiptTimeNs) {
            return false;
        }
        // After the ordering/negative checks, this subtraction cannot overflow.
        return nowElapsedRealtimeNs - hostReceiptTimeNs < staleAfterNs;
    }
}
