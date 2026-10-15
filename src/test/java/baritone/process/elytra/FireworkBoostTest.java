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

public class FireworkBoostTest {
    @Test
    public void pendingRocketPredictionExpiresWithoutServerEntity() {
        for (int cooldown = 10; cooldown > 0; cooldown--) {
            Integer age = FireworkBoost.predictAge(null, cooldown, false);
            assertEquals(Integer.valueOf(10 - cooldown), age);
            assertTrue(new FireworkBoost(age, 10).isBoosted());
        }
        assertNull(FireworkBoost.predictAge(null, 0, false));
        assertFalse(new FireworkBoost(null, 30).isBoosted());
    }

    @Test
    public void nextTickPredictionMatchesTheNextGameTickIncludingExpiry() {
        for (int cooldown = 10; cooldown > 0; cooldown--) {
            assertEquals(FireworkBoost.predictAge(null, cooldown - 1, false),
                    FireworkBoost.predictAge(null, cooldown, true));
        }
    }

    @Test
    public void observedRocketOverridesPredictionAndSurvivesCooldownExpiry() {
        assertEquals(Integer.valueOf(3), FireworkBoost.predictAge(3, 8, false));
        assertEquals(Integer.valueOf(12), FireworkBoost.predictAge(12, 0, false));
        FireworkBoost observed = new FireworkBoost(12, 30);
        assertEquals(18, observed.getGuaranteedBoostTicks());
        assertEquals(29, observed.getMaximumBoostTicks());
        assertEquals(0, new FireworkBoost(50, 30).getGuaranteedBoostTicks());
    }
}
