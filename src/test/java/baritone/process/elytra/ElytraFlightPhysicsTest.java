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

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class ElytraFlightPhysicsTest {
    private static final double XZ_DRAG = 0.9900000095367432;
    private static final double Y_DRAG = 0.9800000190734863;

    @Test
    public void levelFlightFromRestUsesVanillaGravityAndDrag() {
        // At pitch zero the lift cancels 3/4 gravity. Falling diverts 10% of the
        // remaining vertical speed forward; horizontal steering then retains 90%.
        Vec3 motion = ElytraFlightPhysics.step(Vec3.ZERO, new Vec3(0, 0, 1), 0, 0.08, false);
        assertEquals(0, motion.x, 0);
        assertEquals(-0.018 * Y_DRAG, motion.y, 1e-16);
        assertEquals(0.0018 * XZ_DRAG, motion.z, 1e-16);
    }

    @Test
    public void gravityAttributesAndSlowFallingAffectDescent() {
        Vec3 look = new Vec3(0, 0, 1);
        Vec3 normal = ElytraFlightPhysics.step(Vec3.ZERO, look, 0, 0.08, false);
        Vec3 heavy = ElytraFlightPhysics.step(Vec3.ZERO, look, 0, 0.16, false);
        Vec3 slow = ElytraFlightPhysics.step(Vec3.ZERO, look, 0, 0.08, true);
        assertEquals(normal.y * 2, heavy.y, 1e-16);
        assertEquals(normal.z * 2, heavy.z, 1e-16);
        assertEquals(normal.y / 8, slow.y, 1e-16);
        assertEquals(normal.z / 8, slow.z, 1e-16);
        assertEquals(Vec3.ZERO, ElytraFlightPhysics.step(Vec3.ZERO, look, 0, 0, true));
    }

    @Test
    public void slowFallingDoesNotReduceGravityWhileAscending() {
        Vec3 motion = new Vec3(0.2, 0.4, -0.8);
        Vec3 look = new Vec3(0, 0, 1);
        assertEquals(ElytraFlightPhysics.step(motion, look, 0, 0.08, false),
                ElytraFlightPhysics.step(motion, look, 0, 0.08, true));
    }

    @Test
    public void exactVerticalLookNeverDividesByZero() {
        for (float pitch : new float[]{-90, 90}) {
            Vec3 motion = new Vec3(0.5, -0.25, 1.0);
            Vec3 look = new Vec3(0, pitch < 0 ? 1 : -1, 0);
            for (int tick = 0; tick < 100; tick++) {
                motion = ElytraFlightPhysics.step(motion, look, pitch, 0.08, false);
                assertTrue(Double.isFinite(motion.x));
                assertTrue(Double.isFinite(motion.y));
                assertTrue(Double.isFinite(motion.z));
            }
        }
    }
}
