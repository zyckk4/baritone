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

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildLimitPathFinderTest {

    @Test
    public void verticalGoalKeepsTransitionInTheCurrentChunk() {
        BlockPos start = new BlockPos(1_000_007, 300, -2_000_003);
        BlockPos waypoint = BuildLimitPathFinder.transitionUpWaypoint(start, start.above(50), 319);
        assertEquals(start.getX() >> 4, waypoint.getX() >> 4);
        assertEquals(start.getZ() >> 4, waypoint.getZ() >> 4);
        assertEquals(335, waypoint.getY());
    }

    @Test
    public void directionalTransitionRetainsSixteenBlockHorizontalStep() {
        BlockPos start = new BlockPos(1000, 300, -1000);
        BlockPos waypoint = BuildLimitPathFinder.transitionUpWaypoint(start, start.offset(100, 0, 0), 319);
        assertEquals(1016, waypoint.getX());
        assertEquals(-1000, waypoint.getZ());
        assertEquals(335, waypoint.getY());
    }

    @Test
    public void verticalTransitionUsesFloorBasedChunkCentersAtNegativeBoundaries() {
        int[] coordinates = {-33, -32, -17, -16, -1, 0, 15, 16, 31, 32};
        int[] centers = {-40, -24, -24, -8, -8, 8, 8, 24, 24, 40};
        for (int i = 0; i < coordinates.length; i++) {
            BlockPos start = new BlockPos(coordinates[i], 300, coordinates[i]);
            BlockPos waypoint = BuildLimitPathFinder.transitionUpWaypoint(start, start.above(50), 319);
            assertEquals("X center at " + coordinates[i], centers[i], waypoint.getX());
            assertEquals("Z center at " + coordinates[i], centers[i], waypoint.getZ());
            assertEquals(335, waypoint.getY());
        }
    }

    @Test
    public void distanceThresholdUsesBlocksAndIgnoresHeight() {
        BlockPos start = new BlockPos(-1000, 100, 2000);
        assertFalse(BuildLimitPathFinder.isBeyondHorizontalDistance(start, start.above(1000), 32));
        assertFalse(BuildLimitPathFinder.isBeyondHorizontalDistance(start, start.offset(6, 1000, 0), 32));
        assertFalse(BuildLimitPathFinder.isBeyondHorizontalDistance(start, start.offset(32, -1000, 0), 32));
        assertTrue(BuildLimitPathFinder.isBeyondHorizontalDistance(start, start.offset(33, 0, 0), 32));
        assertTrue(BuildLimitPathFinder.isBeyondHorizontalDistance(start, start.offset(24, 0, 24), 32));
    }

    @Test
    public void failedDescentNeverFinishesAtTheTargetsHorizontalPosition() {
        BlockPos start = new BlockPos(1000, 336, -1000);
        for (int horizontalOffset : new int[]{0, 6, 32, 100}) {
            BlockPos destination = start.offset(horizontalOffset, -200, 0);
            UnpackedSegment fallback = BuildLimitPathFinder.fallbackDescentPath(start, destination, 336);
            assertFalse("The target is still below the flight level", fallback.isFinished());
            var path = fallback.collect();
            assertFalse(path.isEmpty());
            path.forEach(node -> assertEquals(336, node.getY()));
        }
    }

    @Test
    public void largeBufferDoesNotOverflowWhenSquared() {
        var path = BuildLimitPathFinder.generateDirectPath(
                new baritone.api.utils.BetterBlockPos(0, 336, 0),
                new baritone.api.utils.BetterBlockPos(50_000, 336, 0),
                100_000, 2, 336);
        assertTrue(path.getB());
        assertEquals(1, path.getA().size());
    }
}
