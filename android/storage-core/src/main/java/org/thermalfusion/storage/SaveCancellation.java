package org.thermalfusion.storage;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation and publish share one fence: a published photo is never called cancelled. */
public final class SaveCancellation {
    private static final int ACTIVE = 0, CANCELLED = 1, COMMITTING = 2, COMMITTED = 3;
    private final AtomicInteger state = new AtomicInteger(ACTIVE);

    public boolean cancel() {
        return state.compareAndSet(ACTIVE, CANCELLED) || state.get() == CANCELLED;
    }

    public void check() {
        if (state.get() == CANCELLED) throw new CancellationException("Save cancelled before publication");
    }

    void commit(Commit action) throws IOException {
        check();
        if (!state.compareAndSet(ACTIVE, COMMITTING)) {
            check();
            throw new IllegalStateException("A save token cannot be reused");
        }
        action.run();
        state.set(COMMITTED);
    }

    @FunctionalInterface interface Commit { void run() throws IOException; }
}
