/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Lifetime of native memory shared by searches, solvers and main-thread queries. */
final class NativeContextLifecycle {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicBoolean destroyStarted = new AtomicBoolean();
    private volatile boolean closed;
    private boolean freed;

    boolean isClosed() {
        return this.closed;
    }

    /** Main-thread queries never wait behind native searches or touch a retired context. */
    boolean tryReadLock() {
        if (this.closed || !this.lock.readLock().tryLock()) return false;
        if (this.closed) {
            this.lock.readLock().unlock();
            return false;
        }
        return true;
    }

    // The monitor serializes cancel/free without waiting for a search's write lock: cancel must be able
    // to interrupt that search. All other pointer uses are protected by lock and a closed check.
    synchronized void invalidate(Runnable cancel) {
        if (!this.closed) {
            this.closed = true;
            cancel.run();
        }
    }

    synchronized void cancel(Runnable cancel) {
        if (!this.closed && !this.freed) {
            cancel.run();
        }
    }

    /**
     * Uses one total budget, including solver/read-lock owners. If anything is still using native memory
     * when it expires, abandon that allocation. Never trade a bounded memory leak for use-after-free.
     */
    boolean destroy(ExecutorService readers, ExecutorService writers, Runnable free, long timeout, TimeUnit unit) {
        if (!this.closed) {
            throw new IllegalStateException("Invalidate before destroying native context");
        }
        if (!this.destroyStarted.compareAndSet(false, true)) {
            return false;
        }
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        readers.shutdownNow();
        writers.shutdownNow();
        try {
            if (!readers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                    || !writers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                    || !this.lock.writeLock().tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) {
                return false;
            }
            try {
                synchronized (this) {
                    free.run();
                    this.freed = true;
                }
                return true;
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
