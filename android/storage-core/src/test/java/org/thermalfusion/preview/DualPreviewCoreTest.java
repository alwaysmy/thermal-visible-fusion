package org.thermalfusion.preview;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free lifecycle, metadata, and receipt-domain tests. */
public final class DualPreviewCoreTest {
    private static int passed;

    private DualPreviewCoreTest() { }

    public static void main(String[] args) {
        geometryTests();
        lifecycleTests();
        timestampTests();
        System.out.println("DualPreviewCoreTest: " + passed + " tests passed");
    }

    private static void geometryTests() {
        run("complete geometry never claims calibration readiness", () -> {
            CameraGeometry geometry = geometry("0", 0);
            check(geometry.hasCompleteMetadata(), "complete fields");
            check(!geometry.calibrationReady(), "metadata is not calibration");
        });
        run("unknown optional geometry is retained as unknown", () -> {
            CameraGeometry geometry = new CameraGeometry("0", 640, 480, null, null,
                    null, null, null, null, null, null);
            check(!geometry.hasCompleteMetadata() && !geometry.calibrationReady(), "unknown ready");
            check(geometry.activeArray() == null && geometry.cropRegion() == null, "invented rect");
        });
        run("each missing geometry field prevents completeness", () -> {
            for (int missing = 0; missing < 8; missing++) {
                CameraGeometry geometry = new CameraGeometry("0", 640, 480,
                        missing == 0 ? null : new int[] {0, 0, 4000, 3000},
                        missing == 1 ? null : new int[] {0, 0, 4000, 3000},
                        missing == 2 ? null : 90, missing == 3 ? null : 0,
                        missing == 4 ? null : 0f, missing == 5 ? null : 0,
                        missing == 6 ? null : 0, missing == 7 ? null : 0);
                check(!geometry.hasCompleteMetadata(), "missing field " + missing);
                check(!geometry.calibrationReady(), "unknown geometry ready");
            }
        });
        run("rectangles copied at input and every read", () -> {
            int[] active = {0, 0, 4000, 3000};
            int[] crop = {100, 200, 3900, 2800};
            CameraGeometry geometry = full(active, crop);
            int originalHash = geometry.hashCode();
            active[2] = 1;
            crop[0] = 3000;
            geometry.activeArray()[0] = 999;
            geometry.cropRegion()[1] = 999;
            check(Arrays.equals(geometry.activeArray(), new int[] {0, 0, 4000, 3000}), "active aliases");
            check(Arrays.equals(geometry.cropRegion(), new int[] {100, 200, 3900, 2800}), "crop aliases");
            check(geometry.hashCode() == originalHash, "hash mutated");
        });
        run("geometry equality is value based", () -> {
            CameraGeometry a = geometry("0", 0);
            CameraGeometry b = geometry("0", 0);
            check(a.equals(a) && a.equals(b) && b.equals(a), "not equal");
            check(a.hashCode() == b.hashCode(), "unequal hashes");
            check(!a.equals(null) && !a.equals("0"), "wrong type equal");
            check(!a.equals(geometry("1", 0)) && !a.equals(geometry("0", 90)), "identity ignored");
            check(!a.equals(full(new int[] {0, 0, 4000, 3000}, new int[] {1, 0, 4000, 3000})),
                    "crop ignored");
        });
        run("missing blank or excessive camera identity rejected", () -> {
            for (String id : new String[] {null, "", "  ", "x".repeat(257)}) {
                rejects(() -> geometry(id, 0));
            }
            check(geometry("x".repeat(256), 0).cameraId.length() == 256, "id boundary");
        });
        run("invalid buffer axes and multiplication bounds rejected", () -> {
            int[][] sizes = {{0, 1}, {1, 0}, {-1, 1}, {1, -1}, {16385, 1}, {1, 16385},
                    {16384, 16384}, {Integer.MAX_VALUE, Integer.MAX_VALUE},
                    {Integer.MIN_VALUE, Integer.MIN_VALUE}};
            for (int[] size : sizes) rejects(() -> unknown(size[0], size[1]));
            check(unknown(16384, 4096).bufferWidth == 16384, "pixel boundary");
            check(unknown(4096, 16384).bufferHeight == 16384, "axis boundary");
        });
        run("invalid sensor rectangles rejected", () -> {
            int[][] rectangles = {{}, {0, 0, 1}, {0, 0, 1, 1, 2}, {-1, 0, 1, 1},
                    {0, -1, 1, 1}, {0, 0, 0, 1}, {0, 0, 1, 0}, {2, 0, 1, 1},
                    {0, 2, 1, 1}, {0, 0, Integer.MIN_VALUE, 1}};
            for (int[] rectangle : rectangles) {
                rejects(() -> full(rectangle, null));
                rejects(() -> full(null, rectangle));
            }
        });
        run("all crop containment edges checked", () -> {
            int[] active = {100, 100, 3900, 2900};
            for (int[] crop : new int[][] {{99, 100, 3900, 2900}, {100, 99, 3900, 2900},
                    {100, 100, 3901, 2900}, {100, 100, 3900, 2901}}) {
                rejects(() -> full(active, crop));
            }
            check(full(active, active).hasCompleteMetadata(), "equal rectangles rejected");
            check(full(active, new int[] {200, 200, 3800, 2800}).hasCompleteMetadata(), "inner crop");
        });
        run("unknown active array does not invent containment knowledge", () -> {
            CameraGeometry geometry = full(null, new int[] {100, 100, 300, 300});
            check(!geometry.hasCompleteMetadata(), "unknown active array");
        });
        run("only degree rotations accepted", () -> {
            for (int rotation : new int[] {0, 90, 180, 270}) {
                check(geometry("0", rotation).displayRotationDegrees == rotation, "valid rotation");
            }
            for (int rotation : new int[] {-90, -1, 1, 2, 3, 45, 360, Integer.MAX_VALUE}) {
                rejects(() -> geometry("0", rotation));
                rejects(() -> new CameraGeometry("0", 640, 480, null, null,
                        rotation, 0, null, null, null, null));
            }
        });
        run("invalid focus distances rejected", () -> {
            for (float focus : new float[] {-1, Float.NaN, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY}) {
                rejects(() -> modes(focus, 0, 0, 0));
            }
            check(modes(0f, 0, 0, 0).focusDistanceDiopters == 0, "infinity focus valid");
        });
        run("mode unknowns allowed and negative modes rejected", () -> {
            check(!modes(0f, null, null, null).hasCompleteMetadata(), "unknown modes");
            rejects(() -> modes(0f, -1, 0, 0));
            rejects(() -> modes(0f, 0, -1, 0));
            rejects(() -> modes(0f, 0, 0, -1));
            check(!modes(0f, 99, 99, 99).calibrationReady(), "unverified mode ready");
        });
    }

    private static void lifecycleTests() {
        run("initial gate has no active streams", () -> {
            DualPreviewGate gate = new DualPreviewGate(100);
            noFrames(gate.snapshot(0));
            check(!gate.snapshot(0).running && !gate.snapshot(0).closed, "initial state");
            check(!gate.isVisibleCurrent(0) && !gate.isInfraredCurrent(0), "zero token accepted");
            stateRejects(() -> gate.beginVisible("0", 0));
            stateRejects(gate::beginInfrared);
        });
        run("invalid expiry rejected", () -> {
            rejects(() -> new DualPreviewGate(0));
            rejects(() -> new DualPreviewGate(-1));
            rejects(() -> new DualPreviewGate(Long.MIN_VALUE));
        });
        run("starting streams is not displaying frames", () -> {
            Fixture f = new Fixture();
            noFrames(f.gate.snapshot(100));
            check(f.gate.snapshot(100).running, "not running");
            check(f.visible != f.infrared, "cross-stream token alias");
        });
        run("two fresh displays provide receipt diagnostic only", () -> {
            Fixture f = new Fixture();
            f.display(100, 102);
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(105);
            check(snapshot.visibleFresh && snapshot.infraredFresh, "fresh panes");
            eq(2, snapshot.receiptDeltaNs, "delta");
            disabled(snapshot);
        });
        run("camera permission denial clears only camera and rejects queued callbacks", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.clearVisible();
            check(!f.visible(101), "late visible callback accepted");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(101);
            check(snapshot.infraredFresh && !snapshot.visibleFresh, "thermal lost");
            check(snapshot.visibleGeometry == null && snapshot.sensorTimestampNs == null
                    && snapshot.visibleReceiptTimeNs == null && snapshot.receiptDeltaNs == null,
                    "visible metadata retained");
            check(f.gate.infraredDisplayed(f.infrared, 102, 102), "thermal session invalidated");
        });
        run("camera error invalidation prevents repeated late callbacks", () -> {
            Fixture f = new Fixture();
            f.gate.clearVisible();
            for (int i = 1; i <= 100; i++) check(!f.visible(i), "late error callback " + i);
            check(f.gate.isInfraredCurrent(f.infrared), "thermal invalidated by camera error");
        });
        run("IR disconnect preserves visible and rejects queued IR", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.clearInfrared();
            check(!f.gate.infraredDisplayed(f.infrared, 101, 101), "late IR accepted");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(101);
            check(snapshot.visibleFresh && !snapshot.infraredFresh, "visible lost");
            check(snapshot.infraredReceiptTimeNs == null && snapshot.receiptDeltaNs == null, "IR retained");
        });
        run("new visible session changes token and preserves IR", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            long replacement = f.gate.beginVisible("0", 0);
            check(replacement != f.visible && !f.visible(101), "old camera accepted");
            check(f.gate.snapshot(101).infraredFresh, "thermal lost");
            check(f.gate.visibleDisplayed(replacement, 102, 102, geometry("0", 0), null, null),
                    "replacement rejected");
        });
        run("camera switch rejects old and mismatched geometry", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            long camera1 = f.gate.beginVisible("1", 0);
            check(!f.visible(101), "old camera callback");
            check(!f.gate.visibleDisplayed(camera1, 101, 101, geometry("0", 0), null, null),
                    "wrong camera metadata");
            check(f.gate.visibleDisplayed(camera1, 102, 102, geometry("1", 0), null, null),
                    "new camera rejected");
            check(f.gate.snapshot(102).visibleGeometry.cameraId.equals("1"), "wrong camera stored");
        });
        run("all rotation changes invalidate prior session", () -> {
            Fixture f = new Fixture();
            long previous = f.visible;
            int time = 0;
            for (int rotation : new int[] {90, 180, 270, 0}) {
                long current = f.gate.beginVisible("0", rotation);
                check(!f.gate.isVisibleCurrent(previous), "old rotation token");
                check(!f.gate.visibleDisplayed(current, ++time, time,
                        geometry("0", (rotation + 90) % 360), null, null), "wrong rotation metadata");
                check(f.gate.visibleDisplayed(current, ++time, time,
                        geometry("0", rotation), null, null), "new rotation rejected");
                previous = current;
            }
        });
        run("per-frame focus crop and buffer change retain current token", () -> {
            Fixture f = new Fixture();
            check(f.visible(100), "initial visible");
            CameraGeometry changed = new CameraGeometry("0", 800, 600,
                    new int[] {0, 0, 4000, 3000}, new int[] {100, 100, 3900, 2900},
                    90, 0, 2f, 1, 1, 1);
            check(f.gate.visibleDisplayed(f.visible, 101, 101, changed, null, null), "dynamic metadata");
            check(f.gate.snapshot(101).visibleGeometry.equals(changed), "not exact displayed geometry");
            disabled(f.gate.snapshot(101));
        });
        run("new IR session invalidates only previous IR token", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            long replacement = f.gate.beginInfrared();
            check(!f.gate.infraredDisplayed(f.infrared, 101, 101), "old IR accepted");
            check(f.gate.isVisibleCurrent(f.visible) && f.gate.snapshot(101).visibleFresh, "visible lost");
            check(f.gate.infraredDisplayed(replacement, 102, 102), "new IR rejected");
        });
        run("stop clears both and rejects late callbacks", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.stop();
            assertStopped(f);
            stateRejects(() -> f.gate.beginVisible("0", 0));
            stateRejects(f.gate::beginInfrared);
        });
        run("background clears both and rejects late callbacks", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.background();
            assertStopped(f);
        });
        run("close is terminal and idempotent", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.close();
            f.gate.close();
            f.gate.stop();
            f.gate.background();
            assertStopped(f);
            check(f.gate.snapshot(101).closed, "lost terminal state");
            stateRejects(f.gate::start);
            stateRejects(() -> f.gate.beginVisible("0", 0));
        });
        run("restart invalidates both former tokens", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.stop();
            f.gate.start();
            noFrames(f.gate.snapshot(101));
            check(!f.visible(101) && !f.gate.infraredDisplayed(f.infrared, 101, 101), "old run accepted");
            long visible = f.gate.beginVisible("0", 0);
            long infrared = f.gate.beginInfrared();
            check(visible != f.visible && infrared != f.infrared, "generation reused");
        });
        run("duplicate start is a deliberate new foreground generation", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            f.gate.start();
            noFrames(f.gate.snapshot(101));
            check(!f.gate.isVisibleCurrent(f.visible) && !f.gate.isInfraredCurrent(f.infrared),
                    "start left old tokens");
        });
        run("stream tokens cannot be used for other stream", () -> {
            Fixture f = new Fixture();
            check(!f.gate.infraredDisplayed(f.visible, 100, 100), "camera token accepted as IR");
            check(!f.gate.visibleDisplayed(f.infrared, 100, 100, geometry("0", 0), null, null),
                    "IR token accepted as camera");
            check(!f.gate.isVisibleCurrent(-1) && !f.gate.isInfraredCurrent(Long.MAX_VALUE), "bad tokens");
        });
        run("snapshot retains a stable immutable historical value", () -> {
            Fixture f = new Fixture();
            f.display(100, 102);
            DualPreviewGate.Snapshot before = f.gate.snapshot(103);
            f.gate.clearVisible();
            f.gate.clearInfrared();
            check(before.visibleFresh && before.infraredFresh, "snapshot changed");
            eq(100, before.visibleReceiptTimeNs, "old receipt");
            before.visibleGeometry.cropRegion()[0] = 900;
            check(before.visibleGeometry.cropRegion()[0] == 0, "snapshot geometry mutable");
        });
        run("background invalidation visible across conversion thread", () -> {
            Fixture f = new Fixture();
            AtomicBoolean accepted = new AtomicBoolean(true);
            f.gate.background();
            Thread worker = new Thread(() -> accepted.set(f.gate.isInfraredCurrent(f.infrared)));
            worker.start();
            try { worker.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            check(!accepted.get(), "worker observed stale generation");
        });
        run("repeated mixed lifecycle transitions never revive old callbacks", () -> {
            DualPreviewGate gate = new DualPreviewGate(100);
            long oldVisible = 0;
            long oldInfrared = 0;
            for (int i = 0; i < 1000; i++) {
                gate.start();
                long visible = gate.beginVisible("0", (i % 4) * 90);
                long infrared = gate.beginInfrared();
                check(!gate.isVisibleCurrent(oldVisible) && !gate.isInfraredCurrent(oldInfrared),
                        "old token revived at " + i);
                check(gate.visibleDisplayed(visible, i, i, geometry("0", (i % 4) * 90), null, null),
                        "visible rejected");
                check(gate.infraredDisplayed(infrared, i, i), "IR rejected");
                disabled(gate.snapshot(i));
                if (i % 3 == 0) gate.background();
                else if (i % 3 == 1) gate.stop();
                else { gate.clearVisible(); gate.clearInfrared(); }
                noFrames(gate.snapshot(i));
                oldVisible = visible;
                oldInfrared = infrared;
            }
        });
    }

    private static void timestampTests() {
        run("zero receipt is a real frame and absence is null", () -> {
            Fixture f = new Fixture();
            f.display(0, 0);
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(0);
            check(snapshot.visibleFresh && snapshot.infraredFresh, "zero receipt rejected");
            eq(0, snapshot.receiptDeltaNs, "zero delta");
            disabled(snapshot);
        });
        run("same receipt time does not establish exposure synchronization", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            eq(0, f.gate.snapshot(100).receiptDeltaNs, "equal receipts");
            disabled(f.gate.snapshot(100));
        });
        run("strict expiry boundary for each displayed pane", () -> {
            Fixture f = new Fixture();
            f.display(100, 102);
            check(f.gate.snapshot(199).visibleFresh, "premature expiry");
            DualPreviewGate.Snapshot boundary = f.gate.snapshot(200);
            check(!boundary.visibleFresh && boundary.infraredFresh, "wrong per-stream expiry");
            check(boundary.receiptDeltaNs == null, "delta includes stale visible");
            check(!f.gate.snapshot(202).infraredFresh, "IR expiry boundary");
        });
        run("stale candidate receipt rejected before state update", () -> {
            Fixture f = new Fixture();
            check(!f.gate.visibleDisplayed(f.visible, 100, 200, geometry("0", 0), null, null), "stale camera");
            check(!f.gate.infraredDisplayed(f.infrared, 100, 200), "stale IR");
            noFrames(f.gate.snapshot(200));
        });
        run("negative and future host receipts fail closed", () -> {
            for (long[] times : new long[][] {{-1, 100}, {0, -1}, {101, 100},
                    {Long.MIN_VALUE, Long.MAX_VALUE}, {Long.MAX_VALUE, 0}}) {
                Fixture f = new Fixture();
                check(!f.gate.visibleDisplayed(f.visible, times[0], times[1], geometry("0", 0), null, null),
                        "invalid visible host time");
                check(!f.gate.infraredDisplayed(f.infrared, times[0], times[1]), "invalid IR host time");
                noFrames(f.gate.snapshot(100));
            }
        });
        run("invalid now in snapshot hides receipt diagnostics", () -> {
            Fixture f = new Fixture();
            f.display(100, 102);
            for (long now : new long[] {-1, Long.MIN_VALUE, 99}) {
                DualPreviewGate.Snapshot snapshot = f.gate.snapshot(now);
                check(!snapshot.visibleFresh && !snapshot.infraredFresh
                        && snapshot.receiptDeltaNs == null, "future frame considered fresh");
                disabled(snapshot);
            }
        });
        run("out-of-order or duplicate queued displays cannot replace latest", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            for (long receipt : new long[] {99, 100}) {
                check(!f.gate.visibleDisplayed(f.visible, receipt, 101, geometry("0", 0), null, null),
                        "out-of-order visible accepted");
                check(!f.gate.infraredDisplayed(f.infrared, receipt, 101), "out-of-order IR accepted");
            }
            eq(100, f.gate.snapshot(101).visibleReceiptTimeNs, "camera receipt regressed");
            eq(100, f.gate.snapshot(101).infraredReceiptTimeNs, "IR receipt regressed");
        });
        run("extreme valid host delta and expiry avoid overflow", () -> {
            DualPreviewGate gate = new DualPreviewGate(Long.MAX_VALUE);
            gate.start();
            long visible = gate.beginVisible("0", 0);
            long infrared = gate.beginInfrared();
            check(gate.visibleDisplayed(visible, 0, 0, geometry("0", 0), null, null), "zero receipt");
            check(gate.infraredDisplayed(infrared, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1), "large receipt");
            eq(Long.MAX_VALUE - 1, gate.snapshot(Long.MAX_VALUE - 1).receiptDeltaNs, "delta overflow");
            check(gate.snapshot(Long.MAX_VALUE).receiptDeltaNs == null, "expiry overflow");
        });
        run("near maximum receipt remains valid at one nanosecond age", () -> {
            Fixture f = new Fixture();
            f.display(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1);
            check(f.gate.snapshot(Long.MAX_VALUE).visibleFresh, "maximum visible rejected");
            eq(0, f.gate.snapshot(Long.MAX_VALUE).receiptDeltaNs, "maximum delta");
        });
        run("missing camera sensor timestamp does not invent exposure time", () -> {
            Fixture f = new Fixture();
            check(f.gate.visibleDisplayed(f.visible, 100, 100, geometry("0", 0), null, null), "missing sensor");
            check(f.gate.infraredDisplayed(f.infrared, 102, 102), "IR");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(103);
            eq(2, snapshot.receiptDeltaNs, "receipt-only delta");
            check(snapshot.sensorTimestampNs == null
                    && snapshot.sensorTimestampSource == DualPreviewGate.CameraTimestampSource.UNKNOWN,
                    "invented exposure metadata");
            disabled(snapshot);
        });
        run("unknown camera sensor timebase never compared to host clock", () -> {
            Fixture f = new Fixture();
            check(f.gate.visibleDisplayed(f.visible, 100, 100, geometry("0", 0), Long.MAX_VALUE,
                    DualPreviewGate.CameraTimestampSource.UNKNOWN), "unknown timebase compared");
            check(f.gate.infraredDisplayed(f.infrared, 102, 102), "IR");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(103);
            eq(2, snapshot.receiptDeltaNs, "sensor timestamp used as receipt");
            eq(Long.MAX_VALUE, snapshot.sensorTimestampNs, "sensor metadata changed");
            disabled(snapshot);
        });
        run("REALTIME sensor metadata still does not enter receipt delta", () -> {
            Fixture f = new Fixture();
            check(f.gate.visibleDisplayed(f.visible, 100, 100, geometry("0", 0), 1L,
                    DualPreviewGate.CameraTimestampSource.REALTIME), "sensor receipt");
            check(f.gate.infraredDisplayed(f.infrared, 102, 102), "IR");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(103);
            eq(2, snapshot.receiptDeltaNs, "mixed clocks");
            eq(1, snapshot.sensorTimestampNs, "sensor timestamp missing");
            disabled(snapshot);
        });
        run("negative sensor timestamps fail closed in every timebase", () -> {
            for (DualPreviewGate.CameraTimestampSource source : DualPreviewGate.CameraTimestampSource.values()) {
                Fixture f = new Fixture();
                check(!f.gate.visibleDisplayed(f.visible, 100, 100, geometry("0", 0), -1L, source),
                        "negative sensor timestamp accepted");
                noFrames(f.gate.snapshot(100));
            }
        });
        run("REALTIME sensor timestamp after host receipt fails closed", () -> {
            Fixture f = new Fixture();
            check(!f.gate.visibleDisplayed(f.visible, 100, 102, geometry("0", 0), 101L,
                    DualPreviewGate.CameraTimestampSource.REALTIME), "future sensor timestamp");
            noFrames(f.gate.snapshot(102));
        });
        run("missing geometry cannot enter diagnostics", () -> {
            Fixture f = new Fixture();
            check(!f.gate.visibleDisplayed(f.visible, 100, 100, null, null, null), "missing geometry");
            check(!f.gate.visibleDisplayed(f.visible, 100, 100, unknown(640, 480), null, null),
                    "unknown display rotation");
            check(f.gate.infraredDisplayed(f.infrared, 100, 100), "IR should remain usable");
            check(f.gate.snapshot(100).receiptDeltaNs == null, "missing geometry paired");
        });
        run("incomplete optional geometry permits receipt-only diagnostics", () -> {
            Fixture f = new Fixture();
            CameraGeometry partial = new CameraGeometry("0", 640, 480, null, null,
                    null, 0, null, null, null, null);
            check(f.gate.visibleDisplayed(f.visible, 100, 100, partial, null, null), "partial geometry");
            check(f.gate.infraredDisplayed(f.infrared, 101, 101), "IR");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(102);
            eq(1, snapshot.receiptDeltaNs, "receipt delta");
            check(!snapshot.visibleGeometry.hasCompleteMetadata(), "unknowns fabricated");
            disabled(snapshot);
        });
        run("callback receipt is not changed to later render timestamp", () -> {
            Fixture f = new Fixture();
            check(f.gate.visibleDisplayed(f.visible, 100, 150, geometry("0", 0), null, null), "queued visible");
            check(f.gate.infraredDisplayed(f.infrared, 110, 155), "queued IR");
            DualPreviewGate.Snapshot snapshot = f.gate.snapshot(160);
            eq(100, snapshot.visibleReceiptTimeNs, "render time substituted");
            eq(110, snapshot.infraredReceiptTimeNs, "IR render time substituted");
            eq(10, snapshot.receiptDeltaNs, "delta used render time");
            check(!f.gate.snapshot(200).visibleFresh, "render incorrectly refreshed age");
        });
        run("late discarded callback cannot refresh freshness", () -> {
            Fixture f = new Fixture();
            f.display(100, 100);
            check(!f.gate.infraredDisplayed(f.infrared, 99, 198), "reordered IR accepted");
            check(!f.gate.snapshot(200).infraredFresh, "late callback refreshed stale IR");
        });
    }

    private static CameraGeometry geometry(String id, int rotation) {
        return new CameraGeometry(id, 640, 480, new int[] {0, 0, 4000, 3000},
                new int[] {0, 0, 4000, 3000}, 90, rotation, 0f, 0, 0, 0);
    }

    private static CameraGeometry full(int[] active, int[] crop) {
        return new CameraGeometry("0", 640, 480, active, crop, 90, 0, 0f, 0, 0, 0);
    }

    private static CameraGeometry unknown(int width, int height) {
        return new CameraGeometry("0", width, height, null, null, null, null, null, null, null, null);
    }

    private static CameraGeometry modes(Float focus, Integer video, Integer optical, Integer distortion) {
        return new CameraGeometry("0", 640, 480, new int[] {0, 0, 4000, 3000},
                new int[] {0, 0, 4000, 3000}, 90, 0, focus, video, optical, distortion);
    }

    private static final class Fixture {
        final DualPreviewGate gate = new DualPreviewGate(100);
        final long visible;
        final long infrared;

        Fixture() {
            gate.start();
            visible = gate.beginVisible("0", 0);
            infrared = gate.beginInfrared();
        }

        boolean visible(long receipt) {
            return gate.visibleDisplayed(visible, receipt, receipt, geometry("0", 0), null, null);
        }

        void display(long visibleReceipt, long infraredReceipt) {
            check(visible(visibleReceipt), "visible receipt rejected");
            check(gate.infraredDisplayed(infrared, infraredReceipt, infraredReceipt), "IR receipt rejected");
        }
    }

    private static void assertStopped(Fixture f) {
        check(!f.visible(101) && !f.gate.infraredDisplayed(f.infrared, 101, 101), "late callback accepted");
        check(!f.gate.isVisibleCurrent(f.visible) && !f.gate.isInfraredCurrent(f.infrared), "token active");
        check(!f.gate.snapshot(101).running, "gate running");
        noFrames(f.gate.snapshot(101));
    }

    private static void disabled(DualPreviewGate.Snapshot snapshot) {
        check(!snapshot.fusionAllowed && !snapshot.calibrationReady && !snapshot.exposureSynchronized,
                "preview diagnostics enabled fusion/calibration/synchronization");
    }

    private static void noFrames(DualPreviewGate.Snapshot snapshot) {
        check(!snapshot.visibleFresh && !snapshot.infraredFresh, "unexpected fresh frame");
        check(snapshot.visibleReceiptTimeNs == null && snapshot.infraredReceiptTimeNs == null
                && snapshot.receiptDeltaNs == null && snapshot.visibleGeometry == null
                && snapshot.sensorTimestampNs == null, "metadata not cleared");
        disabled(snapshot);
    }

    private static void eq(long expected, Long actual, String message) {
        check(actual != null && actual == expected, message + ": expected " + expected + " got " + actual);
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void stateRejects(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected IllegalStateException");
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
