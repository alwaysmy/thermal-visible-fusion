package org.thermalfusion.preview;

/** Converts tightly packed UYVY 4:2:2 display pixels to opaque ARGB pixels.
 *
 * <p>This is an integer approximation of BT.601 limited-range Y'CbCr display
 * conversion. Each four-byte group is U, Y0, V, Y1. It is not radiometric
 * decoding, does not produce temperatures, and must not be used as a
 * temperature or sensor calibration operation. The source must actually use
 * this byte order and color encoding; formats cannot be inferred from bytes.
 */
public final class UyvyConverter {
    /** Conservative per-axis guard against untrusted frame metadata. */
    public static final int MAX_DIMENSION = 4096;
    /** Bounds one output allocation to 16 MiB, in addition to caller input. */
    public static final int MAX_PIXELS = 4 * 1024 * 1024;

    private UyvyConverter() { }

    /**
     * Returns a new row-major array of {@code 0xffRRGGBB} pixels.
     *
     * <p>The input is read-only and is neither retained nor modified. The
     * caller must prevent concurrent writes while this method runs, including
     * reuse of a vendor callback buffer. There is no row stride or padding:
     * source length must be exactly {@code width * height * 2}. Width must be
     * even because neighboring pixels share chroma; height may be odd.
     *
     * @throws IllegalArgumentException if the source is null, dimensions are
     *         invalid or exceed the limits, or packed length does not match
     */
    public static int[] toArgb(byte[] uyvy, int width, int height) {
        if (uyvy == null) {
            throw new IllegalArgumentException("UYVY source must not be null");
        }
        if (width <= 0 || height <= 0 || (width & 1) != 0) {
            throw new IllegalArgumentException("Width must be positive and even; height must be positive");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Frame dimension exceeds " + MAX_DIMENSION);
        }
        // Use long arithmetic before narrowing or allocating, even though the
        // per-axis limit already protects the current multiplication.
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new IllegalArgumentException("Frame pixel count exceeds " + MAX_PIXELS);
        }
        long expectedBytes = pixels * 2L;
        if (uyvy.length != expectedBytes) {
            throw new IllegalArgumentException("Expected exactly " + expectedBytes + " packed UYVY bytes");
        }

        int[] argb = new int[(int) pixels];
        for (int src = 0, dst = 0; src < uyvy.length; src += 4, dst += 2) {
            int u = (uyvy[src] & 0xff) - 128;
            int v = (uyvy[src + 2] & 0xff) - 128;
            argb[dst] = pixel(uyvy[src + 1] & 0xff, u, v);
            argb[dst + 1] = pixel(uyvy[src + 3] & 0xff, u, v);
        }
        return argb;
    }

    private static int pixel(int y, int u, int v) {
        int c = y - 16;
        int r = clamp((298 * c + 409 * v + 128) >> 8);
        int g = clamp((298 * c - 100 * u - 208 * v + 128) >> 8);
        int b = clamp((298 * c + 516 * u + 128) >> 8);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
