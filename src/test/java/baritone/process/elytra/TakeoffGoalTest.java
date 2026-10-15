/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import org.junit.Test;

import static org.junit.Assert.*;

public class TakeoffGoalTest {
    @Test
    public void netherOceanHeightStillRequiresDescent() {
        TakeoffGoal goal = new TakeoffGoal(31, 0, true);
        assertFalse(goal.isInGoal(0, 31, 0));
        assertFalse(goal.isInGoal(0, 24, 0));
        assertTrue(goal.isInGoal(0, 23, 0));
        assertEquals(0, goal.heuristic(0, 0, 0), 0);
    }

    @Test
    public void negativeOverworldHeightAndWorldBottomNeverSatisfyStartingGoal() {
        for (int feetY = -64; feetY <= 320; feetY++) {
            TakeoffGoal goal = new TakeoffGoal(feetY, -64, false);
            assertFalse("starting height " + feetY, goal.isInGoal(10, feetY, -10));
            assertTrue(goal.heuristic(10, feetY, -10) > 0);
        }
        TakeoffGoal belowZero = new TakeoffGoal(-55, -64, false);
        assertFalse(belowZero.isInGoal(0, -63, 0));
        assertTrue(belowZero.isInGoal(0, -64, 0));
    }

    @Test
    public void belowTargetNeverAsksToClimbBackUp() {
        TakeoffGoal goal = new TakeoffGoal(80, 0, true);
        for (int y = 0; y <= 31; y++) {
            assertTrue(goal.isInGoal(-100, y, 100));
            assertEquals(0, goal.heuristic(-100, y, 100), 0);
        }
        assertFalse(goal.isInGoal(0, 32, 0));
    }
}
