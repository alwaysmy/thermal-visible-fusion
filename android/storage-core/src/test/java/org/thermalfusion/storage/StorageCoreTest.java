package org.thermalfusion.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/** No Android, JUnit, Gradle or network needed. These tests exercise production core code. */
public final class StorageCoreTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("success closes before publication and never deletes", () -> {
            Fake target = new Fake();
            SaveCancellation token = new SaveCancellation();
            equal("content://test/1", save(target, stream -> { stream.write(123); return true; }, token));
            equal(Arrays.asList("create", "open", "flush", "close", "publish"), target.events);
            equal(1, target.bytes.size());
            truth(!token.cancel(), "a committed save must not be cancelled");
        });
        test("null insert is a failure with nothing to delete", () -> {
            Fake target = new Fake(); target.insertNull = true;
            expect(IOException.class, () -> save(target, stream -> true, new SaveCancellation()));
            equal(Arrays.asList("create"), target.events);
        });
        test("insert exception does not attempt unknown-row deletion", () -> {
            Fake target = new Fake(); target.insertFailure = true;
            expect(IOException.class, () -> save(target, stream -> true, new SaveCancellation()));
            equal(Arrays.asList("create"), target.events);
        });
        test("null output cleans pending row", () -> {
            Fake target = new Fake(); target.openNull = true;
            failedAndCleaned(target, stream -> true, IOException.class);
        });
        test("open exception cleans pending row", () -> {
            Fake target = new Fake(); target.openFailure = true;
            failedAndCleaned(target, stream -> true, IOException.class);
        });
        test("compress false is not success", () -> {
            Fake target = new Fake(); failedAndCleaned(target, stream -> false, IOException.class);
            truth(target.events.contains("close"), "must close failed encoder stream");
        });
        test("partial encoding exception cleans pending row", () -> {
            Fake target = new Fake();
            failedAndCleaned(target, stream -> { stream.write(10); throw new IOException("encode"); }, IOException.class);
            equal(1, target.bytes.size());
        });
        test("encoding runtime exception cleans pending row", () -> {
            failedAndCleaned(new Fake(), stream -> { throw new IllegalStateException("encode"); }, IllegalStateException.class);
        });
        test("encoding Error also attempts cleanup", () -> {
            failedAndCleaned(new Fake(), stream -> { throw new AssertionError("encode"); }, AssertionError.class);
        });
        test("flush failure cleans pending row", () -> {
            Fake target = new Fake(); target.flushFailure = true;
            failedAndCleaned(target, stream -> true, IOException.class);
        });
        test("close failure prevents publication and cleans row", () -> {
            Fake target = new Fake(); target.closeFailure = true;
            failedAndCleaned(target, stream -> true, IOException.class);
        });
        test("publication failure cleans pending row", () -> {
            Fake target = new Fake(); target.publishFailure = true;
            expect(IOException.class, () -> save(target, stream -> true, new SaveCancellation()));
            equal(1, target.deletes);
            truth(!target.published, "failed publication must not report success");
        });
        test("cleanup exception preserves original failure and locator", () -> {
            Fake target = new Fake(); target.deleteFailure = true;
            IOException failure = expect(IOException.class, () -> save(target, stream -> false, new SaveCancellation()));
            truth(failure.getMessage().contains("compression"), "must preserve primary exception");
            equal(1, failure.getSuppressed().length);
            truth(failure.getSuppressed()[0].getMessage().contains("content://test/1"), "must expose cleanup locator");
        });
        test("cancellation before insert performs no storage mutation", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation(); token.cancel();
            expect(CancellationException.class, () -> save(target, stream -> true, token));
            equal(0, target.events.size());
        });
        test("cancellation just after insert cleans the pending row", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation(); target.afterCreate = token::cancel;
            expect(CancellationException.class, () -> save(target, stream -> true, token));
            equal(Arrays.asList("create", "delete"), target.events);
        });
        test("cancellation after opening closes stream and cleans row", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation(); target.afterOpen = token::cancel;
            expect(CancellationException.class, () -> save(target, stream -> { throw new AssertionError("must not encode"); }, token));
            equal(Arrays.asList("create", "open", "close", "delete"), target.events);
        });
        test("cancellation during encoding cleans partial output", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation();
            expect(CancellationException.class, () -> save(target, stream -> { stream.write(99); token.cancel(); return true; }, token));
            equal(1, target.deletes);
            truth(!target.published, "must not publish cancelled output");
        });
        test("cancellation after close still prevents publication", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation(); target.afterClose = token::cancel;
            expect(CancellationException.class, () -> save(target, stream -> true, token));
            equal(1, target.deletes);
            truth(!target.published, "close is not the commit point");
        });
        test("cancellation at publish fence cannot claim committed photo was cancelled", () -> {
            Fake target = new Fake(); SaveCancellation token = new SaveCancellation();
            target.atPublish = () -> truth(!token.cancel(), "publication owns commit fence");
            save(target, stream -> true, token);
            truth(target.published, "must finish commit"); equal(0, target.deletes);
        });
        test("cancelled-save cleanup failure is visible", () -> {
            Fake target = new Fake(); target.deleteFailure = true;
            SaveCancellation token = new SaveCancellation(); target.afterCreate = token::cancel;
            CancellationException failure = expect(CancellationException.class, () -> save(target, stream -> true, token));
            equal(1, failure.getSuppressed().length);
        });
        test("legacy Android 23–28 alone needs write permission", () -> {
            for (int api = 23; api <= 28; api++) {
                truth(StoragePolicy.needsLegacyWritePermission(api), "legacy permission " + api);
                truth(!StoragePolicy.usesMediaStore(api), "legacy path " + api);
            }
        });
        test("Android 29+ needs no storage permission", () -> {
            for (int api = 29; api <= 100; api++) {
                truth(!StoragePolicy.needsLegacyWritePermission(api), "no storage permission " + api);
                truth(StoragePolicy.usesMediaStore(api), "modern destination " + api);
            }
        });
        test("unsupported Android and expected album", () -> {
            expect(IllegalArgumentException.class, () -> StoragePolicy.usesMediaStore(22));
            expect(IllegalArgumentException.class, () -> StoragePolicy.needsLegacyWritePermission(22));
            equal("Pictures/ThermalFusion/", StoragePolicy.RELATIVE_PATH);
        });
        System.out.println("PASS: " + passed + " storage transaction and version-policy tests");
    }

    private static String save(Fake target, GalleryTransaction.Encoder encoder, SaveCancellation token) throws IOException {
        return new GalleryTransaction<String>().save(target, encoder, token);
    }
    private static void failedAndCleaned(Fake target, GalleryTransaction.Encoder encoder, Class<? extends Throwable> type) throws Exception {
        expect(type, () -> save(target, encoder, new SaveCancellation()));
        equal(1, target.deletes);
        truth(!target.events.contains("publish"), "failure must not publish");
    }
    private static final class Fake implements GalleryTransaction.Destination<String> {
        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean insertNull, insertFailure, openNull, openFailure, flushFailure, closeFailure, publishFailure, deleteFailure, published;
        int deletes;
        Runnable afterCreate = () -> {}, afterOpen = () -> {}, afterClose = () -> {}, atPublish = () -> {};
        @Override public String create() throws IOException {
            events.add("create"); if (insertFailure) throw new IOException("insert"); afterCreate.run();
            return insertNull ? null : "content://test/1";
        }
        @Override public OutputStream open(String row) throws IOException {
            events.add("open"); if (openFailure) throw new IOException("open"); afterOpen.run();
            if (openNull) return null;
            return new OutputStream() {
                @Override public void write(int value) { bytes.write(value); }
                @Override public void flush() throws IOException { events.add("flush"); if (flushFailure) throw new IOException("flush"); }
                @Override public void close() throws IOException { events.add("close"); afterClose.run(); if (closeFailure) throw new IOException("close"); }
            };
        }
        @Override public void publish(String row) throws IOException {
            events.add("publish"); atPublish.run(); if (publishFailure) throw new IOException("publish"); published = true;
        }
        @Override public void delete(String row) throws IOException {
            events.add("delete"); deletes++; if (deleteFailure) throw new IOException("delete");
        }
    }
    @FunctionalInterface private interface Test { void run() throws Exception; }
    private static void test(String name, Test test) throws Exception { test.run(); passed++; System.out.println("PASS " + name); }
    private static void equal(Object expected, Object actual) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    private static void truth(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static <T extends Throwable> T expect(Class<T> type, Test test) throws Exception {
        try { test.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type + ", no exception was thrown");
    }
}
