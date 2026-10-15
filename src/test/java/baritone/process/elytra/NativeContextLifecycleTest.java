/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the real lifetime gate without loading Minecraft or JNI. */
public class NativeContextLifecycleTest {
    @Test(timeout = 10000)
    public void cancelDoesNotWaitForNativeSearchWriteLock() throws Exception {
        NativeContextLifecycle lifecycle = new NativeContextLifecycle();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> search = owner.submit(() -> {
            lifecycle.lock.writeLock().lock();
            try {
                entered.countDown();
                awaitUninterruptibly(release);
            } finally {
                lifecycle.lock.writeLock().unlock();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse("game-thread queries must not wait behind a native writer", lifecycle.tryReadLock());
            AtomicInteger cancels = new AtomicInteger();
            lifecycle.invalidate(cancels::incrementAndGet);
            assertTrue(lifecycle.isClosed());
            assertEquals(1, cancels.get());
            lifecycle.invalidate(cancels::incrementAndGet);
            lifecycle.cancel(cancels::incrementAndGet);
            assertEquals("late cancel must never touch a revoked pointer", 1, cancels.get());
        } finally {
            release.countDown();
            search.get(5, TimeUnit.SECONDS);
            owner.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void freeWaitsForOutstandingSolverReadLock() throws Exception {
        NativeContextLifecycle lifecycle = new NativeContextLifecycle();
        ExecutorService readers = Executors.newSingleThreadExecutor();
        ExecutorService writers = Executors.newSingleThreadExecutor();
        ExecutorService teardown = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger frees = new AtomicInteger();
        lifecycle.lock.readLock().lock();
        Future<Boolean> result;
        try {
            lifecycle.invalidate(() -> {});
            result = teardown.submit(() -> {
                entered.countDown();
                return lifecycle.destroy(readers, writers, frees::incrementAndGet, 5, TimeUnit.SECONDS);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse("free cannot complete while a solver owns a chunk pointer", result.isDone());
            assertEquals(0, frees.get());
        } finally {
            lifecycle.lock.readLock().unlock();
        }
        try {
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(1, frees.get());
            assertFalse(lifecycle.destroy(readers, writers, frees::incrementAndGet, 1, TimeUnit.SECONDS));
            assertEquals("repeated destroy must not double-free", 1, frees.get());
        } finally {
            teardown.shutdownNow();
            readers.shutdownNow();
            writers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void wedgedNativeSearchIsAbandonedWithinBudget() throws Exception {
        NativeContextLifecycle lifecycle = new NativeContextLifecycle();
        ExecutorService readers = Executors.newSingleThreadExecutor();
        ExecutorService writers = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> search = writers.submit(() -> {
            lifecycle.lock.writeLock().lock();
            try {
                entered.countDown();
                awaitUninterruptibly(release); // JNI may ignore a Java interrupt.
            } finally {
                lifecycle.lock.writeLock().unlock();
            }
        });
        AtomicInteger frees = new AtomicInteger();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lifecycle.invalidate(() -> {});
            long start = System.nanoTime();
            assertFalse(lifecycle.destroy(readers, writers, frees::incrementAndGet, 30, TimeUnit.MILLISECONDS));
            assertTrue("teardown must not wait forever", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertEquals(0, frees.get());
            assertTrue(lifecycle.isClosed());
        } finally {
            release.countDown();
            search.get(5, TimeUnit.SECONDS);
            readers.shutdownNow();
            writers.shutdownNow();
        }
        assertFalse(lifecycle.destroy(readers, writers, frees::incrementAndGet, 1, TimeUnit.SECONDS));
        assertEquals("a retired allocation must not be freed by a late second destroy", 0, frees.get());
    }

    @Test(timeout = 10000)
    public void staleMainThreadPointerAlsoUsesTheTeardownBudget() throws Exception {
        NativeContextLifecycle lifecycle = new NativeContextLifecycle();
        ExecutorService readers = Executors.newSingleThreadExecutor();
        ExecutorService writers = Executors.newSingleThreadExecutor();
        ExecutorService teardown = Executors.newSingleThreadExecutor();
        AtomicInteger frees = new AtomicInteger();
        lifecycle.lock.readLock().lock();
        try {
            lifecycle.invalidate(() -> {});
            Future<Boolean> result = teardown.submit(() -> lifecycle.destroy(readers, writers, frees::incrementAndGet, 30, TimeUnit.MILLISECONDS));
            assertFalse(result.get(2, TimeUnit.SECONDS));
            assertEquals(0, frees.get());
        } finally {
            lifecycle.lock.readLock().unlock();
            teardown.shutdownNow();
            readers.shutdownNow();
            writers.shutdownNow();
        }
    }

    @Test
    public void lateCallbackChecksClosedWhileHoldingTheLock() {
        NativeContextLifecycle lifecycle = new NativeContextLifecycle();
        ExecutorService readers = Executors.newSingleThreadExecutor();
        ExecutorService writers = Executors.newSingleThreadExecutor();
        AtomicInteger nativeCalls = new AtomicInteger();
        Runnable callback = () -> {
            if (!lifecycle.tryReadLock()) return;
            try {
                nativeCalls.incrementAndGet();
            } finally {
                lifecycle.lock.readLock().unlock();
            }
        };
        callback.run();
        assertEquals(1, nativeCalls.get());
        lifecycle.invalidate(() -> {});
        assertTrue(lifecycle.destroy(readers, writers, () -> {}, 1, TimeUnit.SECONDS));
        callback.run();
        assertEquals(1, nativeCalls.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
