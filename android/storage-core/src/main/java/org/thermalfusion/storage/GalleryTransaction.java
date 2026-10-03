package org.thermalfusion.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** The same tested transaction is used by both actual Android destinations. */
public final class GalleryTransaction<R> {
    public interface Destination<R> {
        R create() throws IOException;
        OutputStream open(R row) throws IOException;
        void publish(R row) throws IOException;
        void delete(R row) throws IOException;
    }

    @FunctionalInterface public interface Encoder {
        /** Mirrors Bitmap.compress: false is a failure, not a successful empty image. */
        boolean encode(OutputStream output) throws IOException;
    }

    public R save(Destination<R> destination, Encoder encoder, SaveCancellation cancellation)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(encoder);
        Objects.requireNonNull(cancellation);
        cancellation.check();
        R row = destination.create();
        if (row == null) throw new IOException("Storage provider returned no new item");
        try {
            cancellation.check();
            try (OutputStream output = destination.open(row)) {
                if (output == null) throw new IOException("Storage provider returned no output stream");
                cancellation.check();
                if (!encoder.encode(output)) throw new IOException("PNG compression returned false");
                output.flush();
            } // Must close successfully BEFORE publishing.
            cancellation.commit(() -> destination.publish(row));
            return row;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                destination.delete(row);
            } catch (IOException | RuntimeException cleanupFailure) {
                // Do not hide the original failure or pretend cleanup succeeded.
                failure.addSuppressed(new IOException(
                        "Incomplete item cleanup failed: " + row, cleanupFailure));
            }
            throw failure;
        }
    }
}
