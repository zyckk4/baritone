/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import org.junit.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DeferredPathApplicationTest {
    @Test
    public void busyCacheDefersUntilALaterTickWithAReadLease() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> request = queue.begin();
        AtomicBoolean held = new AtomicBoolean();
        AtomicInteger applied = new AtomicInteger();
        AtomicBoolean completedOutsideLease = new AtomicBoolean();
        request.future().thenRun(() -> completedOutsideLease.set(!held.get()));
        queue.offer(request, "path", null);
        for (int tick = 0; tick < 3; tick++) {
            assertFalse(queue.drain(() -> false, () -> fail("No lease was acquired"), path -> applied.incrementAndGet()));
            assertFalse(request.future().isDone());
            assertEquals(0, applied.get());
        }
        assertTrue(queue.drain(() -> { held.set(true); return true; }, () -> held.set(false), path -> {
            assertTrue(held.get());
            assertEquals("path", path);
            assertFalse(request.future().isDone());
            applied.incrementAndGet();
        }));
        request.future().join();
        assertTrue(completedOutsideLease.get());
        assertEquals(1, applied.get());
        assertFalse(queue.drain(() -> true, () -> {}, path -> fail("Applied twice")));
    }

    @Test
    public void supersededRequestCannotReplaceTheLatestPath() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> first = queue.begin();
        queue.offer(first, "old", null);
        DeferredPathApplication.Request<String> latest = queue.begin();
        assertTrue(first.future().isCancelled());
        queue.offer(latest, "latest", null);
        queue.offer(first, "late old native result", null);
        AtomicReference<String> applied = new AtomicReference<>();
        assertTrue(queue.drain(() -> true, () -> {}, applied::set));
        assertEquals("latest", applied.get());
        latest.future().join();
    }

    @Test
    public void cancelDiscardsPendingAndLateNativeResults() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> request = queue.begin();
        queue.cancel();
        queue.offer(request, "late", null);
        assertTrue(request.future().isCancelled());
        assertFalse(queue.drain(() -> true, () -> {}, path -> fail("Canceled path applied")));
    }

    @Test
    public void failureCompletesWithoutAcquiringGeometryLock() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> request = queue.begin();
        RuntimeException expected = new RuntimeException("native search failed");
        queue.offer(request, null, expected);
        assertTrue(queue.drain(() -> { fail("Failure needs no geometry lease"); return false; }, () -> {}, path -> fail("Failure applied")));
        try {
            request.future().join();
            fail("Expected failure");
        } catch (CompletionException ex) {
            assertSame(expected, ex.getCause());
        }
    }

    @Test
    public void replacementCreatedDuringApplicationIsNotDetached() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> first = queue.begin();
        queue.offer(first, "first", null);
        AtomicReference<DeferredPathApplication.Request<String>> next = new AtomicReference<>();
        queue.drain(() -> true, () -> {}, path -> {
            next.set(queue.begin());
            queue.offer(next.get(), "second", null);
        });
        assertTrue(first.future().isCancelled());
        assertFalse(next.get().future().isDone());
        queue.drain(() -> true, () -> {}, path -> assertEquals("second", path));
        next.get().future().join();
    }

    @Test
    public void cancellationListenerCanEnqueueANewRequest() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> first = queue.begin();
        AtomicReference<DeferredPathApplication.Request<String>> next = new AtomicReference<>();
        first.future().whenComplete((value, failure) -> {
            next.set(queue.begin());
            queue.offer(next.get(), "replacement", null);
        });
        queue.cancel();
        assertTrue(first.future().isCancelled());
        queue.drain(() -> true, () -> {}, path -> assertEquals("replacement", path));
        next.get().future().join();
    }

    @Test
    public void applicationFailureReleasesLeaseBeforeCompleting() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> request = queue.begin();
        AtomicBoolean released = new AtomicBoolean();
        RuntimeException expected = new RuntimeException("geometry failure");
        queue.offer(request, "path", null);
        request.future().whenComplete((ignored, failure) -> assertTrue(released.get()));
        queue.drain(() -> true, () -> released.set(true), path -> { throw expected; });
        try {
            request.future().join();
            fail("Expected application failure");
        } catch (CompletionException ex) {
            assertSame(expected, ex.getCause());
        }
        assertTrue(released.get());
    }

    @Test
    public void completionListenerCanEnqueueAnotherPath() {
        DeferredPathApplication<String> queue = new DeferredPathApplication<>();
        DeferredPathApplication.Request<String> first = queue.begin();
        AtomicReference<DeferredPathApplication.Request<String>> next = new AtomicReference<>();
        first.future().thenRun(() -> {
            next.set(queue.begin());
            queue.offer(next.get(), "second", null);
        });
        queue.offer(first, "first", null);
        queue.drain(() -> true, () -> {}, path -> assertEquals("first", path));
        assertNotNull(next.get());
        assertFalse(next.get().future().isDone());
        assertTrue(queue.drain(() -> true, () -> {}, path -> assertEquals("second", path)));
        next.get().future().join();
    }
}
