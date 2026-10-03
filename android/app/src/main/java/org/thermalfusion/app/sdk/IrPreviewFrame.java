package org.thermalfusion.app.sdk;

import org.thermalfusion.preview.UyvyConverter;

/** Immutable copied callback, host timing only. No temperature conversion or exposure time. */
public final class IrPreviewFrame {
    public final int width, height;
    public final long sequence, hostReceiptTimeNs;
    public final String mode;
    public final String selectedUsbIdentity;
    public final int y16SampleCount, parameterByteCount;
    private final byte[] uyvy;
    private final short[] y16;
    private final byte[] parameterLine;

    public IrPreviewFrame(int width, int height, long sequence, long hostReceiptTimeNs,
            String mode, String selectedUsbIdentity, byte[] uyvy, short[] y16, byte[] parameterLine) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 4_194_304 || (width & 1) != 0
                || uyvy == null || uyvy.length != (long) width * height * 2) {
            throw new IllegalArgumentException("Invalid packed UYVY preview dimensions or buffer");
        }
        if (y16 != null && y16.length != (long) width * height) {
            throw new IllegalArgumentException("Invalid mode-specific Y16 buffer length");
        }
        if (parameterLine != null && parameterLine.length > width * 8) {
            throw new IllegalArgumentException("Unverified parameter-line length");
        }
        this.width = width;
        this.height = height;
        this.sequence = sequence;
        this.hostReceiptTimeNs = hostReceiptTimeNs;
        this.mode = mode;
        this.selectedUsbIdentity = selectedUsbIdentity;
        this.uyvy = uyvy.clone();
        this.y16 = y16 == null ? null : y16.clone();
        this.parameterLine = parameterLine == null ? null : parameterLine.clone();
        y16SampleCount = y16 == null ? 0 : y16.length;
        parameterByteCount = parameterLine == null ? 0 : parameterLine.length;
    }

    public int[] displayArgb() { return UyvyConverter.toArgb(uyvy, width, height); }
}
