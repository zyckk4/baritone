/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Normalize failures propagated through chained path futures before deciding to stop a flight. */
public final class ElytraAsyncFailure {
    private ElytraAsyncFailure() {}

    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    public static boolean isCancellation(Throwable failure) {
        return unwrap(failure) instanceof CancellationException;
    }
}
