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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class UnpackedSegmentRejoinTest {
    private final BetterBlockPos start = new BetterBlockPos(0, 70, 0);
    private final BetterBlockPos stopped = new BetterBlockPos(4, 70, 0);
    private final BetterBlockPos tail = new BetterBlockPos(40, 70, 0);

    @Test
    public void unfinishedRecalculationCannotJumpAcrossAnObstacleToOldTail() {
        UnpackedSegment result = new UnpackedSegment(Stream.of(start, stopped), false)
                .appendAfterRejoin(Stream.of(tail), true, true);
        assertFalse(result.isFinished());
        assertEquals(List.of(start, stopped), result.collect());
    }

    @Test
    public void completedRejoinKeepsOldTailAndItsCompletionState() {
        for (boolean complete : new boolean[]{false, true}) {
            UnpackedSegment result = new UnpackedSegment(Stream.of(start, stopped), true)
                    .appendAfterRejoin(Stream.of(tail), true, complete);
            assertEquals(complete, result.isFinished());
            assertEquals(List.of(start, stopped, tail), result.collect());
        }
    }

    @Test
    public void FullRecalculationDoesNotInheritPreviousCompletion() {
        for (boolean complete : new boolean[]{false, true}) {
            UnpackedSegment result = new UnpackedSegment(Stream.of(start, stopped), complete)
                    .appendAfterRejoin(Stream.of(tail), false, !complete);
            assertEquals(complete, result.isFinished());
            assertEquals(List.of(start, stopped), result.collect());
        }
    }
}
