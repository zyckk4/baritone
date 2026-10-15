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

/** Completion and retry state for a route that may need additional native path segments. */
final class PathContinuationState {
    static final int FAILURE_RETRY_TICKS = 20;

    private boolean complete;
    private int retryTicks;

    void acceptPath(boolean finished) {
        this.complete = finished;
        this.retryTicks = 0;
    }

    void calculationFailed() {
        // Failure to reach the next node is never evidence that the destination was reached.
        this.complete = false;
        this.retryTicks = FAILURE_RETRY_TICKS;
    }

    void tick() {
        if (this.retryTicks > 0) {
            this.retryTicks--;
        }
    }

    boolean retryReady() {
        return this.retryTicks == 0;
    }

    boolean isComplete() {
        return this.complete;
    }
}
