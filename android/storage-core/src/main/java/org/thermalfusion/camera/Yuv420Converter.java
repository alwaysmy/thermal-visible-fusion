package org.thermalfusion.camera;

import java.nio.ByteBuffer;

/** Stride/crop-aware YUV_420_888 display conversion. No Android dependency.
 * Uses approximate BT.601 limited-range color, not calibrated/radiometric data.
 * The three planes must be Y, U, V; positions define their first sample.
 * Buffers are read synchronously and neither retained nor advanced.
 */
public final class Yuv420Converter {
    public static final int MAX_DIMENSION = 1920;
    public static final int MAX_PIXELS = 1280 * 720;
    private Yuv420Converter() { }

    public static void copyToArgb(int imageWidth, int imageHeight,
            int cropLeft, int cropTop, int cropRight, int cropBottom,
            ByteBuffer y, int yRowStride, int yPixelStride,
            ByteBuffer u, int uRowStride, int uPixelStride,
            ByteBuffer v, int vRowStride, int vPixelStride, int[] output) {
        if (imageWidth <= 0 || imageHeight <= 0 || imageWidth > MAX_DIMENSION
                || imageHeight > MAX_DIMENSION || (long) imageWidth * imageHeight > MAX_PIXELS) {
            throw new IllegalArgumentException("Invalid or oversized YUV image");
        }
        if (cropLeft < 0 || cropTop < 0 || cropRight > imageWidth || cropBottom > imageHeight
                || cropRight <= cropLeft || cropBottom <= cropTop) {
            throw new IllegalArgumentException("Invalid YUV crop");
        }
        int width = cropRight - cropLeft;
        int height = cropBottom - cropTop;
        if (output == null || output.length < (long) width * height) {
            throw new IllegalArgumentException("Output buffer too small");
        }
        checkPlane(y, yRowStride, yPixelStride, imageWidth, cropRight - 1, cropBottom - 1);
        checkPlane(u, uRowStride, uPixelStride, (imageWidth + 1) / 2,
                (cropRight - 1) / 2, (cropBottom - 1) / 2);
        checkPlane(v, vRowStride, vPixelStride, (imageWidth + 1) / 2,
                (cropRight - 1) / 2, (cropBottom - 1) / 2);
        int outputIndex = 0;
        for (int row = cropTop; row < cropBottom; row++) {
            int yi = y.position() + row * yRowStride;
            int ui = u.position() + (row / 2) * uRowStride;
            int vi = v.position() + (row / 2) * vRowStride;
            for (int col = cropLeft; col < cropRight; col++) {
                int yy = (y.get(yi + col * yPixelStride) & 255) - 16;
                int uu = (u.get(ui + (col / 2) * uPixelStride) & 255) - 128;
                int vv = (v.get(vi + (col / 2) * vPixelStride) & 255) - 128;
                int red = clamp((298 * yy + 409 * vv + 128) >> 8);
                int green = clamp((298 * yy - 100 * uu - 208 * vv + 128) >> 8);
                int blue = clamp((298 * yy + 516 * uu + 128) >> 8);
                output[outputIndex++] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private static void checkPlane(ByteBuffer buffer, int rowStride, int pixelStride,
            int fullWidth, int lastX, int lastY) {
        if (buffer == null || rowStride <= 0 || pixelStride <= 0
                || (long) (fullWidth - 1) * pixelStride + 1 > rowStride) {
            throw new IllegalArgumentException("Invalid plane strides");
        }
        // Only sample bytes are required: last row padding may be unmapped.
        long last = (long) buffer.position() + (long) lastY * rowStride + (long) lastX * pixelStride;
        if (last >= buffer.limit() || last > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Truncated YUV plane");
        }
    }

    private static int clamp(int channel) { return Math.max(0, Math.min(255, channel)); }
}
