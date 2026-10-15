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

import static org.junit.Assert.*;

public class PathContinuationStateTest {
    @Test
    public void failedExtensionNeverMeansArrival() {
        PathContinuationState state = new PathContinuationState();
        state.acceptPath(false);
        state.calculationFailed();
        assertFalse("A failed extension must not authorize arrival or landing", state.isComplete());
        assertFalse("The next tick must not immediately launch another native search", state.retryReady());
    }

    @Test
    public void failedExtensionsRetryAtMostOncePerCooldownAndStayIncomplete() {
        PathContinuationState state = new PathContinuationState();
        state.acceptPath(false);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(state.retryReady());
            state.calculationFailed();
            for (int tick = 0; tick < PathContinuationState.FAILURE_RETRY_TICKS; tick++) {
                assertFalse(state.retryReady());
                assertFalse(state.isComplete());
                state.tick();
            }
            assertTrue(state.retryReady());
            assertFalse(state.isComplete());
        }
    }

    @Test
    public void successfulFinishedPathIsRequiredToCompleteAfterFailure() {
        PathContinuationState state = new PathContinuationState();
        state.acceptPath(false);
        state.calculationFailed();
        state.acceptPath(false);
        assertFalse(state.isComplete());
        assertTrue(state.retryReady());
        state.acceptPath(true);
        assertTrue(state.isComplete());
    }

    @Test
    public void acceptedReplacementResetsOldRetryDelay() {
        PathContinuationState state = new PathContinuationState();
        state.calculationFailed();
        state.tick();
        state.acceptPath(false);
        assertTrue(state.retryReady());
        assertFalse(state.isComplete());
        for (int tick = 0; tick < 100; tick++) state.tick();
        assertTrue(state.retryReady());
    }

    @Test
    public void failedRepairRevokesCompletionOfAnObstructedRoute() {
        PathContinuationState state = new PathContinuationState();
        state.acceptPath(true);
        state.calculationFailed();
        assertFalse(state.isComplete());
        assertFalse(state.retryReady());
    }
}
