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

package baritone.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CachedRegionTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void failedWritePreservesPreviousRegionAndCleansTemporaryFile() throws Exception {
        Path region = temporary.getRoot().toPath().resolve("r.0.0.bcr");
        byte[] previous = {1, 2, 3, 4};
        Files.write(region, previous);
        try {
            CachedRegion.writeAtomically(region, pending -> {
                Files.write(pending, new byte[]{9, 8});
                assertArrayEquals(previous, Files.readAllBytes(region));
                throw new IOException("injected incomplete gzip write");
            });
            fail("The failed writer must not publish a replacement");
        } catch (IOException expected) {
            assertEquals("injected incomplete gzip write", expected.getMessage());
        }
        assertArrayEquals(previous, Files.readAllBytes(region));
        assertOnlyRegionRemains(region);
    }

    @Test
    public void publishesClosedCompleteGzipStream() throws Exception {
        Path region = temporary.getRoot().toPath().resolve("r.0.0.bcr");
        Files.write(region, new byte[]{0});
        CachedRegion.writeAtomically(region, pending -> {
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(pending)))) {
                output.writeInt(456022911);
                output.writeUTF("complete cached region");
            }
            assertArrayEquals(new byte[]{0}, Files.readAllBytes(region));
        });
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(region)))) {
            assertEquals(456022911, input.readInt());
            assertEquals("complete cached region", input.readUTF());
            assertEquals(-1, input.read());
        }
        assertOnlyRegionRemains(region);
    }

    @Test
    public void concurrentWritersUseDifferentTemporaryFiles() throws Exception {
        Path region = temporary.getRoot().toPath().resolve("r.0.0.bcr");
        Files.write(region, new byte[]{0});
        CyclicBarrier bothWriting = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Path> first = executor.submit(() -> writeConcurrently(region, bothWriting, (byte) 1));
            Future<Path> second = executor.submit(() -> writeConcurrently(region, bothWriting, (byte) 2));
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            byte[] published = Files.readAllBytes(region);
            assertEquals(4096, published.length);
            assertTrue(published[0] == 1 || published[0] == 2);
            for (byte value : published) {
                assertEquals(published[0], value);
            }
            assertOnlyRegionRemains(region);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Path writeConcurrently(Path region, CyclicBarrier barrier, byte value) throws IOException {
        Path[] used = new Path[1];
        CachedRegion.writeAtomically(region, pending -> {
            used[0] = pending;
            byte[] contents = new byte[4096];
            java.util.Arrays.fill(contents, value);
            Files.write(pending, contents);
            assertArrayEquals(new byte[]{0}, Files.readAllBytes(region));
            try {
                // Both callbacks must run concurrently; publication must not hold a lock while writing.
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        });
        return used[0];
    }

    private void assertOnlyRegionRemains(Path region) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(region.getParent())) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    public void blockPosSaving() {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    byte part1 = (byte) (z << 4 | x);
                    byte part2 = (byte) (y);
                    byte xz = part1;
                    int X = xz & 0x0f;
                    int Z = (xz >>> 4) & 0x0f;
                    int Y = part2 & 0xff;
                    if (x != X || y != Y || z != Z) {
                        System.out.println(x + " " + X + " " + y + " " + Y + " " + z + " " + Z);
                    }
                    assertEquals(x, X);
                    assertEquals(y, Y);
                    assertEquals(z, Z);
                }
            }
        }
    }
}
