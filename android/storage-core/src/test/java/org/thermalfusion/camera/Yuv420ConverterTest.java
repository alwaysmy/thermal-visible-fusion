package org.thermalfusion.camera;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Dependency-free executable tests; no camera/device/SDK needed. */
public final class Yuv420ConverterTest {
    private static int assertions;
    public static void main(String[] args) {
        blackAndWhite();
        chromaAndStrides();
        oddCropAndBufferPosition();
        interleavedChromaViews();
        oddImageAndDirectReadOnlyPlanes();
        rejectedInputs();
        System.out.println("YUV420 converter: " + assertions + " assertions passed");
    }

    private static void blackAndWhite() {
        int[] out = new int[4];
        ByteBuffer y = bytes(16, 235, 16, 235);
        ByteBuffer u = bytes(128), v = bytes(128);
        Yuv420Converter.copyToArgb(2, 2, 0, 0, 2, 2, y, 2, 1, u, 1, 1, v, 1, 1, out);
        check(Arrays.equals(out, new int[] { 0xff000000, 0xffffffff, 0xff000000, 0xffffffff }), "limited-range neutral endpoints");
        check(y.position() == 0 && u.position() == 0 && v.position() == 0, "input positions unchanged");
        check((out[0] >>> 24) == 255, "opaque alpha");
    }

    private static void chromaAndStrides() {
        // Final row has no trailing mapped padding. U/V sample strides differ.
        ByteBuffer y = bytes(81, 81, 81, 81, 7, 7, 81, 81, 81, 81);
        ByteBuffer u = bytes(90, 3, 128);
        ByteBuffer v = bytes(240, 128);
        int[] out = new int[8];
        Yuv420Converter.copyToArgb(4, 2, 0, 0, 4, 2, y, 6, 1, u, 4, 2, v, 2, 1, out);
        check(out[0] == 0xffff0000 && out[1] == 0xffff0000 && out[4] == 0xffff0000, "red from chroma");
        check(out[2] == 0xff4c4c4c && out[7] == 0xff4c4c4c, "distinct chroma samples and stride padding");
    }

    private static void oddCropAndBufferPosition() {
        ByteBuffer y = bytes(99, 99, 16, 16, 16, 16, 77, 235, 235, 235, 235, 77, 81, 81, 81, 81, 77, 16, 16, 16, 16);
        y.position(2);
        ByteBuffer u = bytes(99, 128, 90, 6, 128, 90);
        ByteBuffer v = bytes(99, 128, 240, 6, 128, 240);
        u.position(1); v.position(1);
        int[] out = new int[6];
        Yuv420Converter.copyToArgb(4, 4, 1, 1, 4, 3, y, 5, 1, u, 3, 1, v, 3, 1, out);
        check(out[0] == 0xffffffff, "crop starts at exact odd x/y source coordinate");
        check(out[3] == 0xff4c4c4c && out[4] == 0xffff0000 && out[5] == 0xffff0000, "crop chroma floor uses original coordinates");
        check(y.position() == 2 && u.position() == 1 && v.position() == 1, "nonzero positions preserved");
    }

    private static void interleavedChromaViews() {
        ByteBuffer uv = bytes(90, 240, 128, 128);
        ByteBuffer u = uv.duplicate();
        u.limit(3);
        ByteBuffer v = uv.duplicate();
        v.position(1);
        int[] out = new int[8];
        Yuv420Converter.copyToArgb(4, 2, 0, 0, 4, 2, bytes(81, 81, 81, 81, 81, 81, 81, 81), 4, 1, u, 4, 2, v, 4, 2, out);
        check(out[0] == 0xffff0000 && out[2] == 0xff4c4c4c, "overlapping interleaved chroma buffers");
        check(v.position() == 1 && u.limit() == 3, "chroma aliases not mutated");
    }

    private static void oddImageAndDirectReadOnlyPlanes() {
        ByteBuffer y = ByteBuffer.allocateDirect(15);
        for (int i = 0; i < 15; i++) y.put((byte) 235);
        y.flip();
        ByteBuffer u = bytes(128, 128, 128, 128).asReadOnlyBuffer();
        ByteBuffer v = u.duplicate();
        int[] out = new int[9];
        // Exercise padded, non-unit luma sampling, odd dimensions and read-only/direct buffers.
        Yuv420Converter.copyToArgb(3, 3, 0, 0, 3, 3, y.asReadOnlyBuffer(), 5, 2,
                u, 2, 1, v, 2, 1, out);
        for (int pixel : out) check(pixel == 0xffffffff, "odd-sized direct/read-only plane pixel");
    }

    private static void rejectedInputs() {
        reject(() -> convert(2, 2, 0, 0, 2, 2, bytes(16, 16, 16), 2, 1, new int[4]), "truncated luma");
        reject(() -> convert(2, 2, 0, 0, 2, 2, bytes(16, 16, 16, 16), 1, 1, new int[4]), "row too short");
        reject(() -> convert(2, 2, 0, 0, 2, 2, bytes(16, 16, 16, 16), 2, 0, new int[4]), "zero pixel stride");
        reject(() -> convert(2, 2, -1, 0, 2, 2, bytes(16, 16, 16, 16), 2, 1, new int[4]), "negative crop");
        reject(() -> convert(2, 2, 0, 0, 3, 2, bytes(16, 16, 16, 16), 2, 1, new int[4]), "outside crop");
        reject(() -> convert(2, 2, 1, 0, 1, 2, bytes(16, 16, 16, 16), 2, 1, new int[4]), "empty crop");
        reject(() -> convert(2, 2, 0, 0, 2, 2, bytes(16, 16, 16, 16), 2, 1, new int[3]), "short output");
        reject(() -> convert(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 2, 2, bytes(1), Integer.MAX_VALUE, 1, new int[4]), "dimension overflow guard");
        reject(() -> convert(1280, 721, 0, 0, 2, 2, bytes(1), 1280, 1, new int[4]), "pixel count bound");
        reject(() -> convert(2, 2, 0, 0, 2, 2, bytes(16, 16, 16, 16), Integer.MAX_VALUE, 1, new int[4]), "stride overflow guard");
        reject(() -> convert(2, 2, 0, 0, 2, 2, null, 2, 1, new int[4]), "null plane");
        reject(() -> Yuv420Converter.copyToArgb(2, 2, 0, 0, 2, 2, bytes(16, 16, 16, 16), 2, 1,
                bytes(), 1, 1, bytes(128), 1, 1, new int[4]), "empty chroma");
    }

    private static void convert(int w, int h, int l, int t, int r, int b, ByteBuffer y, int row, int pixel, int[] out) {
        Yuv420Converter.copyToArgb(w, h, l, t, r, b, y, row, pixel, bytes(128), 1, 1, bytes(128), 1, 1, out);
    }
    private static ByteBuffer bytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length);
        for (int v : values) buffer.put((byte) v);
        buffer.flip();
        return buffer;
    }
    private static void reject(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(label);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }
}
