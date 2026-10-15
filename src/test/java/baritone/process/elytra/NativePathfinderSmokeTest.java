/*
 * This file is part of Baritone.
 * Baritone is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3 or later.
 * Baritone is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/>.
 */
package baritone.process.elytra;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.Octree;
import dev.babbaj.pathfinder.PathSegment;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Locale;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Actual native 1.6 calls against synthetic chunks, without Minecraft or a server. This tests the JNI
 * dependency, not NetherPathfinderContext's game integration. NativeContextLifecycleTest separately
 * exercises the production lifetime gate with controlled concurrent readers/writers.
 */
public class NativePathfinderSmokeTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void requireSupportedNativePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean supportedOs = os.contains("windows") || os.contains("linux") || os.contains("mac");
        boolean supportedArch = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("aarch64") || arch.equals("arm64");
        assumeTrue("native 1.6 has no binary for this platform", supportedOs && supportedArch
                && "64".equals(System.getProperty("sun.arch.data.model")));
        // A broken/missing binary on a supported platform must fail CI, rather than silently skip it.
        assertTrue("native 1.6 failed to load on a supported platform", NetherPathfinder.isThisSystemSupported());
    }

    @Test(timeout = 15000)
    public void overworldContextSupportsItsFullHeight() throws Exception {
        exerciseDimension(NetherPathfinder.DIMENSION_OVERWORLD, 384);
    }

    @Test(timeout = 15000)
    public void netherContextSupportsRoofLimitedHeight() throws Exception {
        exerciseDimension(NetherPathfinder.DIMENSION_NETHER, 128);
    }

    @Test(timeout = 15000)
    public void endContextSupportsItsFullHeight() throws Exception {
        exerciseDimension(NetherPathfinder.DIMENSION_END, 256);
    }

    private void exerciseDimension(int dimension, int height) throws Exception {
        // Exercise the default custom allocator and the documented fallback. Never read a real map cache.
        for (boolean customAllocator : new boolean[]{true, false}) {
            String cache = temporary.newFolder("cache-" + dimension + "-" + customAllocator).getAbsolutePath();
            long context = NetherPathfinder.newContext(0L, cache, dimension, height, customAllocator);
            assertNotEquals("native context allocation", 0L, context);
            try {
                assertFalse(NetherPathfinder.hasChunkFromJava(context, 0, 0));
                long chunk = NetherPathfinder.allocateAndInsertChunk(context, 0, 0);
                assertNotEquals("native chunk allocation", 0L, chunk);
                assertEquals(chunk, NetherPathfinder.getChunk(context, 0, 0));
                assertTrue(NetherPathfinder.hasChunkFromJava(context, 0, 0));

                assertFalse(Octree.getBlock(chunk, 15, height - 1, 15));
                Octree.setBlock(chunk, 15, height - 1, 15, true);
                assertTrue("highest supported block is addressable", Octree.getBlock(chunk, 15, height - 1, 15));
                Octree.setBlock(chunk, 15, height - 1, 15, false);

                double[] from = {4.5, 20.5, 8.5};
                double[] to = {12.5, 20.5, 8.5};
                assertTrue(visible(context, from, to));
                Octree.setBlock(chunk, 8, 20, 8, true);
                assertTrue(Octree.getBlock(chunk, 8, 20, 8));
                assertFalse("raytrace must see an inserted obstacle", visible(context, from, to));
                boolean[] hits = new boolean[1];
                double[] hitPosition = new double[3];
                NetherPathfinder.raytrace(context, NetherPathfinder.CACHE_MISS_SOLID, 1, from, to, hits, hitPosition);
                assertTrue(hits[0]);
                assertEquals(8.0, hitPosition[0], 0.001);
                Octree.setBlock(chunk, 8, 20, 8, false);
                assertTrue("clearing a block must restore visibility", visible(context, from, to));

                PathSegment path = NetherPathfinder.pathFind(context, 2, 18, 2, 10, 18, 10,
                        true, false, 2000, true, 8.0);
                assertNotNull("short route through an empty real chunk", path);
                assertTrue("the short route must reach its target", path.finished);
                assertTrue("native path must contain nodes", path.packed.length > 0);
                // Decode the same packed layout consumed by BetterBlockPos, without loading Minecraft.
                // Do not require exact endpoints: native nodes can represent larger empty octree cubes.
                double[] previous = null;
                for (long packed : path.packed) {
                    int x = (int) (packed >> 38);
                    int y = (int) (packed << 26 >> 52);
                    int z = (int) (packed << 38 >> 38);
                    assertTrue("route stays in the supplied synthetic chunk", x >= 0 && x < 16 && z >= 0 && z < 16);
                    assertTrue("route stays within the supported height", y >= 0 && y < height);
                    assertFalse("path node must be air", Octree.getBlock(chunk, x, y, z));
                    double[] node = {x, y, z};
                    if (previous != null) assertTrue("path nodes must connect through air", visible(context, previous, node));
                    previous = node;
                }
                double[] end = previous;
                assertTrue("finished node must see its destination", visible(context, end, new double[]{10, 18, 10}));

                long farChunk = NetherPathfinder.allocateAndInsertChunk(context, 1024, 1024);
                assertNotEquals(0L, farChunk);
                assertTrue(NetherPathfinder.hasChunkFromJava(context, 1024, 1024));
                NetherPathfinder.cullFarChunks(context, 0, 0, 64);
                assertFalse("culling must remove distant cached chunks", NetherPathfinder.hasChunkFromJava(context, 1024, 1024));
                assertTrue("culling must retain the nearby chunk", NetherPathfinder.hasChunkFromJava(context, 0, 0));
                // Only test that an idle cancel call is valid; this does not prove in-flight native cancellation.
                NetherPathfinder.cancel(context);
            } finally {
                // All JNI work is synchronous here; no worker can retain this pointer after free.
                NetherPathfinder.freeContext(context);
            }
        }
    }

    private static boolean visible(long context, double[] from, double[] to) {
        return NetherPathfinder.isVisible(context, NetherPathfinder.CACHE_MISS_SOLID,
                from[0], from[1], from[2], to[0], to[1], to[2]);
    }

}
