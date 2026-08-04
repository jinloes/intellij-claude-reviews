package com.jinloes.prpilot.review;

import java.util.concurrent.atomic.AtomicBoolean;

/** Records cancellation durably across provider startup and resource publication. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public void throwIfCancelled() throws InterruptedException {
        if (isCancelled()) {
            throw new InterruptedException("Review request cancelled.");
        }
    }
}
