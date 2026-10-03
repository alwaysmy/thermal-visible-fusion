package org.thermalfusion.preview;

import java.util.Arrays;

/** Dependency-free executable checks. Run with scripts/test-preview-core.sh. */
public final class PreviewCoreTest {
    private static int passed;

    public static void main(String[] args) {
        run("limited-range black and white share one chroma pair", () ->
                pixels(new int[] {0xff000000, 0xffffffff}, convert(128, 16, 128, 235)));
        run("neutral grey and unsigned byte decoding", () ->
                pixels(new int[] {0xff828282, 0xffd6d6d6}, convert(128, 128, 128, 200)));
        run("red BT601 vector", () ->
                pixels(new int[] {0xffff0000, 0xffff0000}, convert(90, 81, 240, 81)));
        run("green BT601 vector", () ->
                pixels(new int[] {0xff00ff01, 0xff00ff01}, convert(54, 145, 34, 145)));
        run("blue BT601 vector", () ->
                pixels(new int[] {0xff0000ff, 0xff0000ff}, convert(240, 41, 110, 41)));
        run("below-black and above-white clipping", () ->
                pixels(new int[] {0xff000000, 0xffffffff}, convert(128, 0, 128, 255)));
        run("extreme chroma clips channels and remains opaque", () -> {
            pixels(new int[] {0xffb800ed, 0xffff7dff}, convert(255, 0, 255, 255));
            pixels(new int[] {0xff008700, 0xff4aff14}, convert(0, 0, 0, 255));
        });
        run("row-major order and odd height", () -> pixels(new int[] {
                0xff000000, 0xffffffff, 0xff828282, 0xffd6d6d6, 0xff000000, 0xffffffff
        }, UyvyConverter.toArgb(bytes(128, 16, 128, 235, 128, 128, 128, 200,
                128, 0, 128, 255), 2, 3)));
        run("source immutable and result separately allocated", () -> {
            byte[] source = bytes(90, 81, 240, 81, 240, 41, 110, 41);
            byte[] original = source.clone();
            int[] first = UyvyConverter.toArgb(source, 4, 1);
            int[] second = UyvyConverter.toArgb(source, 4, 1);
            check(Arrays.equals(source, original), "source was modified");
            check(first != second, "output reused a prior array");
            first[0] = 0;
            check(second[0] == 0xffff0000, "output arrays alias");
            source[1] = 0;
            check(second[0] == 0xffff0000, "output retains source dependence");
        });
        run("null source rejected", () -> rejects(() -> UyvyConverter.toArgb(null, 2, 1)));
        run("empty source rejected", () -> rejects(() -> UyvyConverter.toArgb(new byte[0], 2, 1)));
        run("truncated pair rejected", () -> rejects(() -> UyvyConverter.toArgb(new byte[3], 2, 1)));
        run("extra byte rejected", () -> rejects(() -> UyvyConverter.toArgb(new byte[5], 2, 1)));
        run("row padding rejected", () -> rejects(() -> UyvyConverter.toArgb(new byte[12], 2, 2)));
        run("zero dimensions rejected", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[0], 0, 1));
            rejects(() -> UyvyConverter.toArgb(new byte[0], 2, 0));
        });
        run("negative dimensions rejected", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[0], -2, 1));
            rejects(() -> UyvyConverter.toArgb(new byte[0], 2, -1));
            rejects(() -> UyvyConverter.toArgb(new byte[0], -2, -1));
        });
        run("odd width rejected even with an even pixel count", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[4], 1, 2));
            rejects(() -> UyvyConverter.toArgb(new byte[12], 3, 2));
        });
        run("huge dimensions rejected before overflow or allocation", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[0], Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
            rejects(() -> UyvyConverter.toArgb(new byte[0], 65536, 65536));
            rejects(() -> UyvyConverter.toArgb(new byte[0], Integer.MIN_VALUE, Integer.MIN_VALUE));
        });
        run("per-axis limits enforced", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[0], UyvyConverter.MAX_DIMENSION + 2, 1));
            rejects(() -> UyvyConverter.toArgb(new byte[0], 2, UyvyConverter.MAX_DIMENSION + 1));
            check(UyvyConverter.toArgb(new byte[UyvyConverter.MAX_DIMENSION * 2],
                    UyvyConverter.MAX_DIMENSION, 1).length == UyvyConverter.MAX_DIMENSION, "width limit");
            check(UyvyConverter.toArgb(new byte[UyvyConverter.MAX_DIMENSION * 4],
                    2, UyvyConverter.MAX_DIMENSION).length == UyvyConverter.MAX_DIMENSION * 2, "height limit");
        });
        run("pixel cap enforced and exact boundary accepted", () -> {
            rejects(() -> UyvyConverter.toArgb(new byte[0], 2048, 2049));
            check(UyvyConverter.toArgb(new byte[UyvyConverter.MAX_PIXELS * 2], 2048, 2048).length
                    == UyvyConverter.MAX_PIXELS, "pixel cap boundary");
        });
        run("freshness starts at host receipt", () -> {
            check(FrameFreshness.isFresh(100, 100, 10), "just received frame rejected");
            check(FrameFreshness.isFresh(0, 0, 1), "zero timestamp is valid");
        });
        run("freshness strict expiry boundary", () -> {
            check(FrameFreshness.isFresh(100, 109, 10), "early expiry");
            check(!FrameFreshness.isFresh(100, 110, 10), "expiry boundary not stale");
            check(!FrameFreshness.isFresh(100, 111, 10), "expired frame fresh");
        });
        run("invalid receipt or current clock fails closed", () -> {
            check(!FrameFreshness.isFresh(101, 100, 10), "future receipt");
            check(!FrameFreshness.isFresh(-1, 100, 10), "negative receipt");
            check(!FrameFreshness.isFresh(0, -1, 10), "negative now");
            check(!FrameFreshness.isFresh(Long.MIN_VALUE, Long.MAX_VALUE, 10), "invalid timestamp overflow");
        });
        run("freshness extreme valid timestamps do not overflow", () -> {
            check(FrameFreshness.isFresh(Long.MAX_VALUE - 1, Long.MAX_VALUE, 2), "near maximum");
            check(!FrameFreshness.isFresh(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1), "one nanosecond expiry");
            check(FrameFreshness.isFresh(0, Long.MAX_VALUE - 1, Long.MAX_VALUE), "maximum threshold");
            check(!FrameFreshness.isFresh(0, Long.MAX_VALUE, Long.MAX_VALUE), "maximum expiry boundary");
        });
        run("invalid freshness threshold rejected", () -> {
            rejects(() -> FrameFreshness.isFresh(0, 0, 0));
            rejects(() -> FrameFreshness.isFresh(0, 0, -1));
            rejects(() -> FrameFreshness.isFresh(0, 0, Long.MIN_VALUE));
        });
        System.out.println("PreviewCoreTest: " + passed + " tests passed");
    }

    private static int[] convert(int u, int y0, int v, int y1) {
        return UyvyConverter.toArgb(bytes(u, y0, v, y1), 2, 1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static void pixels(int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Expected " + hex(expected) + " but got " + hex(actual));
        }
    }

    private static String hex(int[] pixels) {
        StringBuilder text = new StringBuilder();
        for (int pixel : pixels) text.append(String.format("%08x ", pixel));
        return text.toString();
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void run(String name, Runnable action) {
        action.run();
        passed++;
        System.out.println("PASS " + name);
    }
}
