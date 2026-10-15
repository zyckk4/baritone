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

import java.util.Objects;

final class FireworkBoost {

    static final int PENDING_BOOST_TICKS = 10;

    /** Predict a just-used rocket while its server entity is in flight, then expire that assumption. */
    static Integer predictAge(Integer observedAge, int cooldown, boolean nextTick) {
        if (observedAge != null) {
            return observedAge;
        }
        final int remaining = cooldown - (nextTick ? 1 : 0);
        return remaining > 0 ? PENDING_BOOST_TICKS - remaining : null;
    }

    private final Integer fireworkTicksExisted;
    private final int minimumBoostTicks;
    private final int maximumBoostTicks;

    /**
     * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
     * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
     *                             provide.
     */
    public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
        this.fireworkTicksExisted = fireworkTicksExisted;

        // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
        this.minimumBoostTicks = minimumBoostTicks;
        this.maximumBoostTicks = minimumBoostTicks + 11;
    }

    public boolean isBoosted() {
        return this.fireworkTicksExisted != null;
    }

    /**
     * @return The guaranteed number of remaining ticks with boost
     */
    public int getGuaranteedBoostTicks() {
        return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
    }

    /**
     * @return The maximum number of remaining ticks with boost
     */
    public int getMaximumBoostTicks() {
        return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || o.getClass() != FireworkBoost.class) {
            return false;
        }

        FireworkBoost other = (FireworkBoost) o;
        if (!this.isBoosted() && !other.isBoosted()) {
            return true;
        }

        return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                && this.minimumBoostTicks == other.minimumBoostTicks
                && this.maximumBoostTicks == other.maximumBoostTicks;
    }
}

