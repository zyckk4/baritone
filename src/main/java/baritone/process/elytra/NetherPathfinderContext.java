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

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.utils.accessor.IPalettedContainer;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static net.minecraft.world.level.chunk.LevelChunkSection.SECTION_SIZE;

/**
 * @author Brady
 */
public final class NetherPathfinderContext implements IElytraPathFinder {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    private static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();
    // This lock must be held while there are active pointers to chunks in java,
    // but we just hold it for the entire tick so we don't have to think much about it.
    private final NativeContextLifecycle lifecycle = new NativeContextLifecycle();
    public final ReentrantReadWriteLock rwl = this.lifecycle.lock;
    public final ReentrantReadWriteLock.ReadLock readLock = rwl.readLock();
    public final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();
    private final int maxHeight;

    // Visible for access in BlockStateOctreeInterface
    final long context;
    private final long seed;
    private final Set<CompletableFuture<UnpackedSegment>> pendingPaths = ConcurrentHashMap.newKeySet();
    // write locked operations
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    // operations that don't make changes to the chunk cache. could use multiple threads but i'm not sure if it would cause problems.
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private final ResourceKey<Level> dimension;
    final int minY;
    private final BlockStateOctreeInterface boi;

    public NetherPathfinderContext(long seed, Path cache, Level world) {
        this.dimension = world.dimension();
        this.minY = world.dimensionType().minY();
        final int dim;
        if (this.dimension == Level.NETHER) dim = NetherPathfinder.DIMENSION_NETHER;
        else if (this.dimension == Level.END) dim = NetherPathfinder.DIMENSION_END;
        else dim = NetherPathfinder.DIMENSION_OVERWORLD;
        int height = Math.min(world.dimensionType().height(), 384);
        if (!Baritone.settings().elytraAllowAboveRoof.value && dim == NetherPathfinder.DIMENSION_NETHER) height = Math.min(height, 128);
        this.maxHeight = height;
        this.context = NetherPathfinder.newContext(seed, cache != null ? cache.toString() : null, dim, height, Baritone.settings().elytraCustomAllocator.value);
        this.seed = seed;
        this.boi = new BlockStateOctreeInterface(this);
    }

    public boolean hasChunk(ChunkPos pos) {
        if (!this.tryAcquireReadLock()) return false;
        try {
            return !this.lifecycle.isClosed() && NetherPathfinder.hasChunkFromJava(this.context, pos.x, pos.z);
        } finally {
            readLock.unlock();
        }
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks) {
        this.executeWrite(() -> {
            writeLock.lock();
            try {
                if (this.lifecycle.isClosed()) return;
                this.boi.chunkPtr = 0L;
                NetherPathfinder.cullFarChunks(this.context, chunkX, chunkZ, maxDistanceBlocks);
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        if (chunkIn == null || chunkIn.isEmpty() || this.lifecycle.isClosed()) return;
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        this.executeWrite(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            final LevelChunk chunk = ref.get();
            if (chunk != null) {
                writeLock.lock();
                try {
                    if (this.lifecycle.isClosed()) return;
                    // we might free this chunk
                    this.boi.chunkPtr = 0L;
                    long ptr = NetherPathfinder.allocateAndInsertChunk(this.context, chunk.getPos().x, chunk.getPos().z);
                    writeChunkData(chunk, ptr);
                } finally {
                    writeLock.unlock();
                }
            }
        });
    }

    public void queueBlockUpdate(BlockChangeEvent event) {
        this.executeWrite(() -> {
            ChunkPos chunkPos = event.getChunkPos();
            // not inserting or deleting from the cache hashmap but it would still be bad for this function to race with itself
            writeLock.lock();
            try {
                if (this.lifecycle.isClosed()) return;
                long ptr = NetherPathfinder.getChunk(this.context, chunkPos.x, chunkPos.z);
                if (ptr == 0) return; // this shouldn't ever happen
                event.getBlocks().forEach(pair -> {
                    BlockPos pos = pair.first().below(minY);
                    if (pos.getY() < 0 || pos.getY() >= 384) return;
                    boolean isSolid = pair.second() != AIR_BLOCK_STATE;
                    Octree.setBlock(ptr, pos.getX() & 15, pos.getY(), pos.getZ() & 15, isSolid);
                });
            } finally {
                writeLock.unlock();
            }
        });
    }

    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        final BlockPos adjustedSrc = src.below(minY);
        final BlockPos adjustedDst = dst.below(minY);
        boolean generate = Baritone.settings().elytraPredictTerrain.value && this.dimension == Level.NETHER;
        // Even without terrain prediction, pathFind imports disk regions into the native chunk map.
        // Those inserts can rehash the map: they must exclude every solver/raytrace reader.
        final CompletableFuture<UnpackedSegment> future = new CompletableFuture<>();
        this.pendingPaths.add(future);
        future.whenComplete((result, error) -> this.pendingPaths.remove(future));
        if (this.lifecycle.isClosed()) {
            future.completeExceptionally(new PathCalculationException("Pathfinder context closed"));
            return future;
        }
        try {
            this.writeExecutor.execute(() -> {
                writeLock.lock();
                try {
                    if (this.lifecycle.isClosed()) {
                        throw new PathCalculationException("Pathfinder context closed");
                    }
                    final PathSegment segment = NetherPathfinder.pathFind(
                            this.context,
                            adjustedSrc.getX(), adjustedSrc.getY(), adjustedSrc.getZ(),
                            adjustedDst.getX(), adjustedDst.getY(), adjustedDst.getZ(),
                            !Baritone.settings().elytraAllowTightSpaces.value, // atleastX4
                            false, // refine
                            10000, // timeoutMs
                            !generate, // useAirIfChunkNotLoaded
                            // TODO: Determine appropriate cost value
                            8.0 // fakeChunkCost
                    );
                    if (segment == null) {
                        throw new PathCalculationException("Path calculation failed");
                    }
                    if (this.lifecycle.isClosed()) {
                        throw new PathCalculationException("Pathfinder context closed");
                    }
                    future.complete(new UnpackedSegment(UnpackedSegment.from(segment).collect().stream().map(pos -> pos.above(minY)), segment.finished));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    // Region imports may replace cached chunks. Never retain a Java chunk pointer across them.
                    this.boi.chunkPtr = 0L;
                    writeLock.unlock();
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new PathCalculationException("Pathfinder context closed"));
        }
        return future;
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        final double adjustedStartY = startY - this.minY;
        final double adjustedEndY = endY - this.minY;
        if (!this.tryAcquireReadLock()) return false;
        try {
            return !this.lifecycle.isClosed() && NetherPathfinder.isVisible(this.context, NetherPathfinder.CACHE_MISS_SOLID, startX, adjustedStartY, startZ, endX, adjustedEndY, endZ);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        return raytrace(start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        if (!this.tryAcquireReadLock()) return false;
        try {
            if (this.lifecycle.isClosed()) return false;
            switch (visibility) {
                case Visibility.ALL:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
                case Visibility.NONE:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) == -1;
                case Visibility.ANY:
                    return NetherPathfinder.isVisibleMulti(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, true) != -1;
                default:
                    throw new IllegalArgumentException("lol");
            }
        } finally {
            readLock.unlock();
        }
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        if (!this.tryAcquireReadLock()) {
            Arrays.fill(hitsOut, true);
            return;
        }
        try {
            if (this.lifecycle.isClosed()) {
                Arrays.fill(hitsOut, true);
                return;
            }
            NetherPathfinder.raytrace(this.context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, hitsOut, hitPosOut);
        } finally {
            readLock.unlock();
        }
    }

    public boolean passable(int x, int y, int z) {
        if (!this.tryAcquireReadLock()) return false;
        try {
            if (this.lifecycle.isClosed()) return false;
            // Read locks allow the main thread and background solver concurrently. The cached chunk pointer
            // and its coordinates must still be updated together for each individual query.
            synchronized (this.boi) {
                return !this.boi.get0(x, y, z);
            }
        } finally {
            readLock.unlock();
        }
    }

    public void cancel() {
        this.lifecycle.cancel(() -> NetherPathfinder.cancel(this.context));
    }

    /** Immediately revoke the context; native memory is released later off the game thread. */
    public void invalidate() {
        this.lifecycle.invalidate(() -> NetherPathfinder.cancel(this.context));
        for (CompletableFuture<UnpackedSegment> future : this.pendingPaths) {
            future.completeExceptionally(new PathCalculationException("Pathfinder context closed"));
        }
    }

    private void executeWrite(Runnable task) {
        if (this.lifecycle.isClosed()) return;
        try {
            this.writeExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Cancellation can race submission. Queued tasks also check closed under the write lock.
        }
    }

    public void destroy() {
        this.invalidate();
        // Keep the upstream 10s native search timeout; the total teardown budget also includes readers
        // still holding a native chunk pointer. A timed-out context remains closed and is never freed.
        boolean freed = this.lifecycle.destroy(this.readExecutor, this.writeExecutor, () -> {
            this.boi.chunkPtr = 0L;
            NetherPathfinder.freeContext(this.context);
        }, 15, TimeUnit.SECONDS);
        if (!freed) {
            System.err.println("Elytra native context teardown did not finish; leaving its allocation closed to avoid use-after-free");
        }
    }

    public long getSeed() {
        return this.seed;
    }

    boolean canAccessBlocks() {
        return !this.lifecycle.isClosed() && (this.rwl.getReadHoldCount() > 0 || this.rwl.isWriteLockedByCurrentThread());
    }

    public void acquireReadLock() {
        this.readLock.lock();
    }

    public boolean tryAcquireReadLock() {
        return this.lifecycle.tryReadLock();
    }

    public void releaseReadLock() {
        this.readLock.unlock();
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    private static void writeChunkData(LevelChunk chunk, long chunkPtr) {
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            final int maxSections = Math.min(chunkInternalStorageArray.length, 24); // pathfinder support stops at 384/16 sections
            for (int y0 = 0; y0 < maxSections; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                if (extendedblockstorage == null || extendedblockstorage.hasOnlyAir()) {
                    continue;
                }
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                var palette = iPalettedContainer.getPalette();
                // Mushrooms spawn on the roof and writing them as solid will cause pages to be unnecessarily allocated.
                // idFor can't be used because it may update the palette
                int airId = -1;
                int caveAirId = -1;
                int redMushroomId = -1;
                int brownMushroomId = -1;
                for (int i = 0; i < palette.getSize(); i++) {
                    BlockState bs = palette.valueFor(i);
                    if (bs == Blocks.AIR.defaultBlockState()) airId = i;
                    else if (bs == Blocks.CAVE_AIR.defaultBlockState()) caveAirId = i;
                    else if (bs == Blocks.RED_MUSHROOM.defaultBlockState()) redMushroomId = i;
                    else if (bs == Blocks.BROWN_MUSHROOM.defaultBlockState()) brownMushroomId = i;
                }
                if (airId == -1 & caveAirId == -1) {
                    final long bytesInSection = SECTION_SIZE / 8;
                    UNSAFE.setMemory(chunkPtr + (y0 * bytesInSection), bytesInSection, (byte) 0xFF);
                    continue;
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);

                        // Avoid unnecessary writes that may trigger a page allocation
                        if (!(value == airId | value == caveAirId) & value != redMushroomId & value != brownMushroomId) {
                            Octree.setBlock(chunkPtr, x, y, z, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public static final class Visibility {
        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;
        private Visibility() {}
    }
}
