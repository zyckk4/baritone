/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class ElytraAsyncFailureTest {
    @Test
    public void chainedStaleRequestIsCancellationRatherThanFlightFailure() {
        CancellationException staleRequest = new CancellationException("Elytra path request superseded");
        CompletableFuture<Void> calculation = new CompletableFuture<>();
        CompletableFuture<Void> consumer = calculation.thenRun(() -> fail("stale path must not be applied"));
        calculation.completeExceptionally(staleRequest);
        try {
            consumer.join();
            fail("expected a failed path future");
        } catch (CompletionException failure) {
            assertTrue(ElytraAsyncFailure.isCancellation(failure));
            assertSame(staleRequest, ElytraAsyncFailure.unwrap(failure));
        }
    }

    @Test
    public void nestedCompletionAndExecutionWrappersKeepCancellationIdentity() {
        CancellationException staleRequest = new CancellationException("superseded by lava recovery");
        Throwable wrapped = new CompletionException(new ExecutionException(new CompletionException(staleRequest)));
        assertTrue(ElytraAsyncFailure.isCancellation(wrapped));
        assertSame(staleRequest, ElytraAsyncFailure.unwrap(wrapped));
    }

    @Test
    public void genuinePathFailureAndSuccessfulCompletionAreNotCancellation() {
        PathCalculationException failed = new PathCalculationException("no route");
        Throwable wrapped = new CompletionException(new ExecutionException(failed));
        assertFalse(ElytraAsyncFailure.isCancellation(wrapped));
        assertSame(failed, ElytraAsyncFailure.unwrap(wrapped));
        assertFalse(ElytraAsyncFailure.isCancellation(null));
        assertNull(ElytraAsyncFailure.unwrap(null));
    }
}
