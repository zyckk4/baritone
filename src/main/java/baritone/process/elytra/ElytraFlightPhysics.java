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

import baritone.api.utils.RotationUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class ElytraFlightPhysics {

    private ElytraFlightPhysics() {}

    /**
     * The airborne 1.21.11 vanilla elytra velocity step. Fluids and server corrections are outside this model.
     *
     * @param gravity     The player's {@code Attributes.GRAVITY} value, which is not always the 0.08 default
     * @param slowFalling Whether the player has Slow Falling, which caps gravity while not ascending
     */
    static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch,
                     final double gravity, final boolean slowFalling) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        // vanilla uses the exact Math.cos here and keeps the square in double precision, unlike the Mth table
        double pitchBase3 = Mth.square(Math.cos((double) pitchRadians));
        // LivingEntity.getEffectiveGravity
        double effectiveGravity = slowFalling && motionY <= 0 ? Math.min(gravity, 0.01) : gravity;

        motionY += effectiveGravity * (-1.0 + pitchBase3 * 0.75);
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0 && pitchBase2 > 0) { // negative pitch looks upward
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) {
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.9900000095367432;
        motionY *= 0.9800000190734863;
        motionZ *= 0.9900000095367432;

        return new Vec3(motionX, motionY, motionZ);
    }

}
