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

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One pending path application, owned by the game thread. A busy cache postpones application until
 * a later tick instead of turning a failed lock acquisition into a terrain-obstruction result.
 */
final class DeferredPathApplication<T> {
    private Request<T> current;

    Request<T> begin() {
        Request<T> previous = this.current;
        Request<T> next = new Request<>();
        this.current = next;
        if (previous != null) previous.future.cancel(false);
        return next;
    }

    void offer(Request<T> request, T value, Throwable failure) {
        if (request != this.current || request.future.isDone()) {
            request.future.cancel(false);
            return;
        }
        request.value = value;
        request.failure = failure;
        request.ready = true;
    }

    void cancel() {
        Request<T> previous = this.current;
        this.current = null;
        if (previous != null) previous.future.cancel(false);
    }

    boolean drain(BooleanSupplier acquire, Runnable release, Consumer<T> apply) {
        Request<T> request = this.current;
        if (request == null || !request.ready) return false;
        if (request.future.isDone()) {
            this.current = null;
            return false;
        }
        Throwable failure = request.failure;
        if (failure == null) {
            if (!acquire.getAsBoolean()) return false;
            try {
                apply.accept(request.value);
            } catch (Throwable ex) {
                failure = ex;
            } finally {
                release.run();
            }
        }
        // apply may replace or cancel the request. Never detach the replacement or report a canceled
        // request as a newly applied path. Completion listeners also run only after releasing our lease.
        if (this.current == request) this.current = null;
        if (failure == null) request.future.complete(null);
        else request.future.completeExceptionally(failure);
        return true;
    }

    static final class Request<T> {
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private T value;
        private Throwable failure;
        private boolean ready;

        CompletableFuture<Void> future() {
            return this.future;
        }
    }
}
