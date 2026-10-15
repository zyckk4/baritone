/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;

/** A descent goal must never already be satisfied where takeoff starts. */
public final class TakeoffGoal implements Goal {
    public static final int MIN_FALL_HEIGHT = 8;
    private final int level;

    public TakeoffGoal(int feetY, int minY, boolean nether) {
        int deepest = nether ? minY + 31 : minY;
        this.level = Math.min(feetY - 1, Math.max(minY, Math.min(deepest, feetY - MIN_FALL_HEIGHT)));
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y <= this.level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return y > this.level ? GoalYLevel.calculate(this.level, y) : 0;
    }

    @Override
    public String toString() {
        return "TakeoffGoal{y<=" + this.level + "}";
    }
}
