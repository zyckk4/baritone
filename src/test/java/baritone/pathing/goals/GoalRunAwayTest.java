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

package baritone.pathing.goals;

import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class GoalRunAwayTest {

    @Test
    public void visitsWholeDangerRegionRegardlessOfInputOrder() {
        BlockPos first = new BlockPos(5, 4, 3);
        BlockPos second = new BlockPos(-2, -1, -4);
        for (BlockPos[] points : new BlockPos[][]{{first, second}, {second, first}}) {
            TrackingGoal goal = new TrackingGoal(points);
            goal.heuristic();
            assertArrayEquals(new int[]{-4, -3, -6}, goal.minimum);
            assertArrayEquals(new int[]{7, 6, 5}, goal.maximum);
        }
    }

    private static class TrackingGoal extends GoalRunAway {
        final int[] minimum = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        final int[] maximum = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        TrackingGoal(BlockPos... from) {
            super(2, from);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int[] position = {x, y, z};
            for (int axis = 0; axis < position.length; axis++) {
                minimum[axis] = Math.min(minimum[axis], position[axis]);
                maximum[axis] = Math.max(maximum[axis], position[axis]);
            }
            return 0;
        }
    }
}
