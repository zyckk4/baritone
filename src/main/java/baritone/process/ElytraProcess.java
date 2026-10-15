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

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.*;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private NetherPathfinderContext npfContext;
    private BlockPos pendingDestination;
    private boolean pendingAppendDestination;
    private boolean predictingTerrain;
    private boolean allowTight;
    private boolean allowAboveBuildLimit;
    private boolean allowAboveRoof;
    private final Semaphore npfSema = new Semaphore(1);

    private static final int TAKEOFF_MIN_FALL_HEIGHT = TakeoffGoal.MIN_FALL_HEIGHT;
    private static final int TAKEOFF_STALL_TICKS = 60;
    private static final int STANDING_TAKEOFF_TIMEOUT_TICKS = 40;
    private static final int MAX_STANDING_TAKEOFFS = 3;
    private static final int MAX_WALK_OFF_ATTEMPTS = 2;
    private static final int TAKEOFF_MAX_EXIT_HEIGHT = 32;
    private int takeoffStallTicks;
    private int standingTakeoffs;
    private int walkOffAttempts;
    private int takeoffAirborneTicks;
    private boolean walkOffImpossible;
    private boolean takeoffBoostPending;
    private int takeoffOpenedTicksAgo = -1;
    private boolean lavaPathRequested;

    private static final int SHORT_LANDING_COLUMN_HEIGHT = 15;
    private static final int LONG_LANDING_COLUMN_HEIGHT = 39;
    private static final long LANDING_SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(25); // half a tick
    private int landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();
    private LandingSearchState landingSearchState;

    @Override
    public void onLostControl() {
        onLostControl(true);
    }

    public void onLostControl(boolean destroyNpf) {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.reachedGoal = false;
        this.goal = null;
        this.pendingDestination = null;
        this.takeoffStallTicks = 0;
        this.standingTakeoffs = 0;
        this.walkOffAttempts = 0;
        this.takeoffAirborneTicks = 0;
        this.walkOffImpossible = false;
        this.takeoffBoostPending = false;
        this.takeoffOpenedTicksAgo = -1;
        this.lavaPathRequested = false;
        destroyBehaviorAsync();
        if (destroyNpf) {
            destroyNpfContextAsync();
        }
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null || this.pendingDestination != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String TAKEOFF_ADVICE_MSG = "Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";
    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. " + TAKEOFF_ADVICE_MSG;

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (this.behavior == null) {
            tryStartPendingFlight();
            if (this.behavior == null) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        try {
            final long seedSetting = Baritone.settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.npfContext.getSeed()) {
                logDirect("Nether seed changed, recalculating path");
                this.resetState();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraPredictTerrain setting changed, recalculating path from scratch");
                predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
                this.resetState();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (allowTight != Baritone.settings().elytraAllowTightSpaces.value) {
                logDirect("elytraAllowTightSpaces setting changed, recalculating path from scratch");
                allowTight = Baritone.settings().elytraAllowTightSpaces.value;
                this.resetState();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (allowAboveBuildLimit != Baritone.settings().elytraAllowAboveBuildLimit.value) {
                logDirect("elytraAllowAboveBuildLimit setting changed, recalculating path from scratch");
                allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
                this.resetState();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (allowAboveRoof != Baritone.settings().elytraAllowAboveRoof.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraAllowAboveRoof setting changed, recalculating path from scratch");
                allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
                this.resetState();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        } catch (IllegalArgumentException e) {
            logDirect(e.getMessage(), ChatFormatting.RED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        this.behavior.onTick();

        if (!ctx.player().isFallFlying() && ctx.player().isInLava()) {
            return lavaTakeoff();
        }

        if (calcFailed) {
            if (this.state == State.LOCATE_JUMP || this.state == State.GET_TO_JUMP) {
                return standingTakeoff();
            }
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // No landing business while in lava. The solver is busy climbing out, and every path the landing search
        // asks for from in here starts inside the pool and fails at once, which used to re-issue one every tick.
        final boolean inLava = ctx.player().isInLava();
        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && !inLava && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && !inLava && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                if (this.landingSearchState == null) {
                    logDirect("Path complete, searching for safe landing spot...");
                }
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    logDirect("Found potential landing spot.");
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                    this.goingToLandingSpot = true;
                } else {
                    this.goingToLandingSpot = false;
                }
            }

            if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
                if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (Baritone.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot && landingSpot != null) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - this.landingColumnHeight) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            if (this.state == State.TAKEOFF_JUMP) {
                this.state = State.START_FLYING;
                this.takeoffStallTicks = 0;
                this.takeoffBoostPending = true;
                this.takeoffOpenedTicksAgo = 0;
            }
            if (this.takeoffOpenedTicksAgo >= 0) {
                this.takeoffOpenedTicksAgo++;
            }
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.behavior.npfContext.tryAcquireReadLock()) {
                try {
                    behavior.tick();
                    // The START_FALL_FLYING request was sent on the previous tick. Use only after the
                    // solver has chosen the rotation that the current movement packet will carry.
                    if (this.takeoffBoostPending && this.behavior.useFireworkForTakeoff()) {
                        this.takeoffBoostPending = false;
                    }
                } finally {
                    this.behavior.npfContext.releaseReadLock();
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.takeoffOpenedTicksAgo >= 0) {
            // The glide ended soon after takeoff. This can indicate a server rejection; it is not an
            // acknowledgement of whether the server accepted a rocket.
            if (!ctx.player().onGround()) {
                logDirect("The elytra closed " + this.takeoffOpenedTicksAgo + " ticks after takeoff.");
            }
            this.takeoffOpenedTicksAgo = -1;
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            if (ctx.player().onGround() && Baritone.settings().elytraAutoJump.value) {
                this.state = State.LOCATE_JUMP;
                this.takeoffStallTicks = 0;
            } else {
                this.state = State.START_FLYING;
            }
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.walkOffImpossible) {
                // there is nothing to walk off of around here, and we already spent a couple of seconds of
                // pathfinding finding that out once
                return standingTakeoff();
            }
            if (this.goal == null) {
                this.goal = takeoffGoal();
            }
            IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() != this.goal) {
                // a segment left over from whatever we were doing before takeoff. it can never finish while we're
                // pausing pathing, and while it exists secretInternalSetGoalAndPath refuses to start the walk off
                // calculation, so neither side of this ever moves unless we drop it here
                baritone.getPathingBehavior().secretInternalSegmentCancel();
                executor = null;
            }
            if (executor != null) {
                this.takeoffStallTicks = 0;
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    final ElytraBehavior takeoffBehavior = this.behavior;
                    this.state = State.PAUSE;
                    takeoffBehavior.pathManager.pathToDestination(from).whenCompleteAsync((result, ex) -> {
                        if (this.behavior != takeoffBehavior || this.state != State.PAUSE) return;
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    }, ctx.minecraft()::execute);
                } else {
                    return standingTakeoff();
                }
            } else if (baritone.getPathingBehavior().getInProgress().isPresent()) {
                this.takeoffStallTicks = 0;
            } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                // no path and nothing being calculated: SET_GOAL_AND_PAUSE has already decided it has nothing to
                // do, so the executor we're waiting on is never going to appear
                return standingTakeoff();
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.TAKEOFF_JUMP) {
            if (++this.takeoffStallTicks > STANDING_TAKEOFF_TIMEOUT_TICKS) {
                return abortTakeoff(ctx.player().onGround()
                        ? "Couldn't get off the ground. "
                        : "Got into the air but the elytra wouldn't open. ");
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (ctx.player().onGround()) {
                // an ordinary jump. vanilla physics, and the movement packets it produces are what the server
                // checks its own idea of onGround against before it will accept the request below
                this.takeoffAirborneTicks = 0;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            } else if (this.takeoffAirborneTicks++ > 0) {
                // the second press of the double tap. openElytra() is what actually opens it, but the client
                // reports every change of the key to the server (ServerboundPlayerInputPacket), so the inputs it
                // sees are the ones a player who opens an elytra sends: press, release, press
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                if (openElytra()) {
                    this.state = State.START_FLYING;
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.takeoffStallTicks = 0;
                this.state = State.START_FLYING;
            } else {
                if (executor != null || baritone.getPathingBehavior().getInProgress().isPresent()) {
                    this.takeoffStallTicks = 0;
                } else if (++this.takeoffStallTicks > TAKEOFF_STALL_TICKS) {
                    // the walk off path ran out without us ever falling off it, either because we reached the goal
                    // by stepping down or because the segment got cancelled. PathingBehavior won't re-path once
                    // it considers the goal met, so go pick a new ledge instead of standing here
                    if (++this.walkOffAttempts >= MAX_WALK_OFF_ATTEMPTS) {
                        return standingTakeoff();
                    }
                    this.takeoffStallTicks = 0;
                    this.goal = null;
                    this.state = State.LOCATE_JUMP;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Takes off from where we are standing: jump, open the elytra on the way up, and light a firework on the
     * following tick, since a glide that starts with no speed a block and a half off the floor only ends one way. This
     * is what flat terrain leaves us with, where {@link WalkOffCalculationContext} has no ledge to offer and the
     * alternative is standing around telling the user to go find a cliff.
     */
    private PathingCommand standingTakeoff() {
        this.walkOffImpossible = true;
        this.goal = null;
        this.takeoffStallTicks = 0;
        this.takeoffAirborneTicks = 0;
        baritone.getPathingBehavior().secretInternalSegmentCancel();

        if (!Baritone.settings().elytraStandingTakeoff.value) {
            return abortTakeoff("There is no spot to jump off from, and elytraStandingTakeoff is off. ");
        }
        if (this.standingTakeoffs >= MAX_STANDING_TAKEOFFS) {
            return abortTakeoff("Took off from here " + this.standingTakeoffs + " times and ended up back on the ground every time. ");
        }
        final BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.fullyPassable(ctx, feet.above(2)) || !MovementHelper.fullyPassable(ctx, feet.above(3))) {
            return abortTakeoff("There is no spot to jump off from, and not enough room above me to jump and open the elytra. ");
        }
        if (!this.behavior.selectFirework()) {
            return abortTakeoff("There is no spot to jump off from, and no fireworks in my hotbar to take off from here with. ");
        }
        if (this.standingTakeoffs++ == 0) {
            logDirect("No spot to jump off from, taking off from here instead.");
        }
        // The path starts from the first clear cube straight above us, which is where a climb on the takeoff
        // rocket comes out. In a basalt delta crevice or the hollow beside a lava pond the walls are a couple of
        // blocks away on every side, and up is the one direction that is reliably clear. Left to itself the
        // pathfinder would start from the nearest clear cube to our feet, and its search for one ignores walls:
        // from a hole that is very often a cube on the far side of one, which nothing here could ever reach.
        final BetterBlockPos exit = takeoffExit(feet);
        // the elytra path has to exist before we leave the ground: the behavior does nothing without one, and the
        // handful of ticks between opening the elytra and hitting the ground is no time to compute one
        this.state = State.PAUSE;
        final ElytraBehavior takeoffBehavior = this.behavior;
        takeoffBehavior.pathManager.pathToDestination(exit != null ? exit : feet).whenCompleteAsync((result, ex) -> {
            if (this.behavior != takeoffBehavior || this.state != State.PAUSE) return;
            if (ex == null) {
                this.state = State.TAKEOFF_JUMP;
                return;
            }
            onLostControl();
        }, ctx.minecraft()::execute);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * We are in lava and the elytra is not open. Whatever the ground states were doing can wait: an elytra opens
     * in lava (vanilla only refuses in water), and a rocket lit looking straight up is what gets us out. Swimming
     * is far too slow, and since vanilla moves us by the fluid rules while we are in one, glide or not, the
     * rocket is the only thrust there is and keeps a fraction of its usual push. Pointing it at the pool's wall
     * would spend all of that on basalt, so once the elytra is open {@link ElytraBehavior} aims straight up and
     * keeps lighting rockets until we are out.
     */
    private PathingCommand lavaTakeoff() {
        baritone.getPathingBehavior().secretInternalSegmentCancel();
        baritone.getInputOverrideHandler().clearAllKeys();
        // swimming up: slow, but it also lifts us off the pool floor, without which the elytra can't open
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        if (this.behavior.pathManager.getPath().isEmpty() && !this.lavaPathRequested) {
            // nothing to fly along once we're out; the takeoff states would have computed this
            this.lavaPathRequested = true;
            final BetterBlockPos feet = ctx.playerFeet();
            final BetterBlockPos exit = takeoffExit(feet);
            this.behavior.pathManager.pathToDestination(exit != null ? exit : feet);
        }
        if (!ctx.player().onGround() && openElytra()) {
            this.state = State.START_FLYING;
            this.takeoffStallTicks = 0;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * The centre of the first 4x4x4 cube of air straight above {@code feet}, aligned the way the elytra
     * pathfinder aligns its nodes, or {@code null} if there is none within {@link #TAKEOFF_MAX_EXIT_HEIGHT}.
     */
    private BetterBlockPos takeoffExit(BetterBlockPos feet) {
        final int minY = ctx.world().dimensionType().minY();
        final boolean roofLimited = ctx.world().dimension() == Level.NETHER && !Baritone.settings().elytraAllowAboveRoof.value;
        int maxY = minY + this.npfContext.getMaxHeight();
        if (Baritone.settings().elytraAllowAboveBuildLimit.value && !roofLimited) {
            maxY = Math.max(maxY, feet.y + TAKEOFF_MAX_EXIT_HEIGHT + 4);
        }
        final int limit = Math.min(feet.y + TAKEOFF_MAX_EXIT_HEIGHT, maxY - 4);
        final int ox = feet.x & ~3;
        final int oz = feet.z & ~3;
        final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        cubes:
        for (int oy = feet.y & ~3; oy <= limit; oy += 4) {
            for (int x = ox; x < ox + 4; x++) {
                for (int y = oy; y < oy + 4; y++) {
                    for (int z = oz; z < oz + 4; z++) {
                        mut.set(x, y, z);
                        if (y < minY || !ctx.world().hasChunkAt(mut) || !ctx.world().getBlockState(mut).isAir()) {
                            continue cubes;
                        }
                    }
                }
            }
            return new BetterBlockPos(ox + 2, oy + 2, oz + 2);
        }
        return null;
    }

    /**
     * Opens the elytra mid jump, exactly the way {@link net.minecraft.client.player.LocalPlayer} does it when a
     * player double taps jump: set the flag locally, then tell the server. Doing it here instead of by toggling
     * the jump key means it doesn't hang on the key going up and back down on precisely the right ticks, and it
     * is the same thing on the wire either way - the server has already been sent the movement packets from an
     * ordinary jump, so it agrees we are off the ground by the time the request reaches it.
     *
     * @return {@code true} if the elytra is now open
     */
    private boolean openElytra() {
        if (!ctx.player().tryToStartFallFlying()) {
            return false;
        }
        ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(), ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        // The rocket goes next tick, once the solver has aimed. A vanilla client cannot use an item in the tick
        // it starts gliding (its use packets go out before its glide packet, while the elytra is still shut),
        // and the use packet carries a look that has to match that tick's movement packet, which the solver
        // has not decided yet. The tick costs nothing: the rocket takes a round trip to show up regardless.
        this.takeoffBoostPending = true;
        this.takeoffOpenedTicksAgo = 0;
        return true;
    }

    private PathingCommand abortTakeoff(String reason) {
        onLostControl();
        logDirect(reason + TAKEOFF_ADVICE_MSG);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Where to walk to in order to take off.
     * <p>
     * {@link WalkOffCalculationContext} only offers single steps and falls of at least
     * {@link #TAKEOFF_MIN_FALL_HEIGHT} as ways down, so asking to descend at least that far is what forces the
     * path to contain a {@link MovementFall} at all. Aiming deeper than that is what makes the biggest reachable
     * drop the cheapest route: a long fall costs a handful of ticks where the same descent by single steps costs
     * roughly 8 ticks a block, so the pathfinder will happily walk a long way to an overhang. In the nether the
     * lava ocean at y=31 is as deep as it is worth aiming, since anything that reaches it is already more runway
     * than we need.
     * <p>
     * The level is always strictly below our feet. A goal we are already standing in is never pathed to at all
     * ({@link baritone.behavior.PathingBehavior#secretInternalSetGoalAndPath}), which used to leave the process
     * paused forever waiting on an executor that nothing was calculating - most easily hit by landing at exactly
     * y=31 in the nether, where the old fixed {@code GoalYLevel(31)} was already satisfied.
     */
    private Goal takeoffGoal() {
        return new TakeoffGoal(ctx.playerFeet().y, ctx.world().dimensionType().minY(), ctx.world().dimension() == Level.NETHER);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            behavior.invalidate();
            this.behavior = null;
            Baritone.getExecutor().execute(() -> {
                behavior.destroy();
            });
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.npfContext == null) return;

        ChunkSource chunkProvider = ctx.world().getChunkSource();
        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npfContext.queueForPacking(chunk);
                }
            }
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : this.pendingDestination;
    }

    @Override
    public List<BetterBlockPos> getPath() {
        return this.behavior != null ? behavior.pathManager.getPath() : Collections.emptyList();
    }

    @Override
    public void pathTo(BlockPos destination) {
        if (!isSupportedPos(destination)) {
            throw new IllegalArgumentException("The goal must be within bounds to use elytra flight.");
        }

        if (ctx.player() != null && !isSupportedPos(ctx.playerFeet())) {
            throw new IllegalArgumentException("The player must be within bounds to use elytra flight.");
        }

        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null) {
            return;
        }
        this.onLostControl(false);
        this.predictingTerrain = ctx.player().level().dimension() == Level.NETHER && Baritone.settings().elytraPredictTerrain.value;
        this.allowTight = Baritone.settings().elytraAllowTightSpaces.value;
        this.allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
        this.allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        this.pendingDestination = destination.immutable();
        this.pendingAppendDestination = appendDestination;
        tryStartPendingFlight();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            // ElytraBehavior will automatically change the destination height depending on if we're above or below the roof
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }

        this.pathTo((new BlockPos(x, y, z)));
    }

    private boolean isSupportedPos(BlockPos pos) {
        final boolean isNether = ctx.world().dimension() == Level.NETHER;
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = (isNether && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, ctx.world().dimensionType().height() + minY);

        final boolean aboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        final boolean aboveBuild = Baritone.settings().elytraAllowAboveBuildLimit.value;

        final boolean enforceMaxY = isNether ? !(aboveRoof && aboveBuild) : !aboveBuild;

        if (pos.getY() < minY) {
            return false;
        }

        return !enforceMaxY || pos.getY() < maxY;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return this.behavior == null || !(this.state == State.FLYING || this.state == State.START_FLYING || this.state == State.TAKEOFF_JUMP);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        TAKEOFF_JUMP("Taking off"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() == EventState.PRE) {
            // Persistent caches belong to one world/dimension. Invalidate before the client swaps it.
            onLostControl();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = TAKEOFF_MIN_FALL_HEIGHT;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(Level dim, BlockPos pos) {
        DimensionType dimType = dim.dimensionType();
        int minY = dimType.minY();
        int maxY = (dim.dimension() == Level.NETHER && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, dimType.height() + minY);
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value)
                || block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRASS_BLOCK || block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.TERRACOTTA
                || block == Blocks.SNOW || block == Blocks.ICE || block == Blocks.MYCELIUM || block == Blocks.PODZOL
                || block == Blocks.DARK_OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.END_STONE || block == Blocks.BEDROCK
                || block == Blocks.OBSIDIAN || block == Blocks.COBBLESTONE;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        final boolean useHeightmap = ctx.player().getY() > ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ());
        if (this.landingSearchState == null || !this.landingSearchState.isCompatible(start, useHeightmap)) {
            this.landingSearchState = new LandingSearchState(start, this.behavior.destination, useHeightmap);
        } else {
            this.landingSearchState.updateStartPosition(start);
        }

        BetterBlockPos landingSpot = this.landingSearchState.advance();
        if (landingSpot != null || this.landingSearchState.exhausted) {
            this.landingSearchState = null;
        }
        return landingSpot;
    }

    private boolean isChunkLoaded(BetterBlockPos pos) {
        return ctx.world().getChunkSource().hasChunk(pos.x >> 4, pos.z >> 4);
    }

    private final class LandingSearchState {
        private final BetterBlockPos origin;
        private final boolean useHeightmap;
        private final Queue<BetterBlockPos> queue;
        private final Set<BetterBlockPos> visited = new HashSet<>();
        private final LongOpenHashSet checkedPositions = new LongOpenHashSet();
        private boolean exhausted;

        private LandingSearchState(BetterBlockPos origin, BetterBlockPos dest, boolean useHeightmap) {
            this.origin = origin;
            this.useHeightmap = useHeightmap;

            final BetterBlockPos target = isChunkLoaded(dest) ? dest : origin;
            this.queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - target.x) * (pos.x - target.x) + (pos.z - target.z) * (pos.z - target.z)).thenComparingInt(pos -> -pos.y));
            this.queue.add(target);
        }

        private boolean isCompatible(BetterBlockPos start, boolean useHeightmap) {
            // Restart if we've moved more than a chunk so the priority adjusts and newly loaded chunks get revisited
            return this.useHeightmap == useHeightmap && this.origin.distanceSq(start) <= (16 * 16);
        }

        private void updateStartPosition(BetterBlockPos start) {
            if (this.visited.add(start)) {
                this.queue.add(start);
            }
        }

        private BetterBlockPos advance() {
            final long deadline = System.nanoTime() + LANDING_SEARCH_BUDGET_NANOS;
            while (!this.queue.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                BetterBlockPos qPos = this.queue.poll();
                if (!isChunkLoaded(qPos)) {
                    continue;
                }
                BetterBlockPos landing = this.useHeightmap ? this.advanceHeightmap(qPos) : this.advanceUnderground(qPos);
                if (landing != null) {
                    return landing;
                }
            }
            this.exhausted = true;
            return null;
        }

        private BetterBlockPos advanceUnderground(BetterBlockPos pos) {
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot, landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
                if (this.visited.add(pos.above())) this.queue.add(pos.above());
                if (this.visited.add(pos.below())) this.queue.add(pos.below());
            }
            return null;
        }

        private BetterBlockPos advanceHeightmap(BetterBlockPos qPos) {
            int height = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, qPos.getX(), qPos.getZ());
            BetterBlockPos pos = new BetterBlockPos(qPos.getX(), height + 1, qPos.getZ());
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = ctx.playerFeet().y - actualLandingSpot.y < LONG_LANDING_COLUMN_HEIGHT ? SHORT_LANDING_COLUMN_HEIGHT : LONG_LANDING_COLUMN_HEIGHT;
                    if (hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
            }
            return null;
        }
    }

    private void tryStartPendingFlight() {
        if (this.pendingDestination == null || ctx.world() == null || ctx.player() == null) return;
        if (this.npfContext == null) {
            // The previous context may still be leaving a native search. Never block the game thread on
            // its teardown: the active pending request is retried on the next process tick.
            if (!this.npfSema.tryAcquire()) return;
            try {
                this.npfContext = new NetherPathfinderContext(
                        Baritone.settings().elytraNetherSeed.value,
                        Baritone.settings().elytraUseCache.value ? baritone.getWorldProvider().getCurrentWorld().directory.resolve("cache") : null,
                        ctx.world()
                );
            } catch (RuntimeException | Error error) {
                this.npfSema.release();
                this.pendingDestination = null;
                throw error;
            }
        }
        final BlockPos destination = this.pendingDestination;
        this.pendingDestination = null;
        this.behavior = new ElytraBehavior(this.baritone, this, this.npfContext, destination, this.pendingAppendDestination);
        this.repackChunks();
        this.behavior.pathTo();
    }

    private void destroyNpfContextAsync() {
        NetherPathfinderContext npf = this.npfContext;
        if (npf != null) {
            this.npfContext = null;
            npf.invalidate();
            Baritone.getExecutor().execute(() -> {
                try {
                    npf.destroy();
                } finally {
                    npfSema.release();
                }
            });
        }
    }
}
