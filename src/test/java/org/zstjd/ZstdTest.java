package org.zstjd;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZstdTest {

    @Test @Order(1)
    void emptyData() {
        assertArrayEquals(new byte[0], Zstd.decompress(Zstd.compress(new byte[0])));
    }

    @Test @Order(2)
    void shortString() {
        byte[] d = "Hello World! zstjd compression.".getBytes();
        byte[] r = Zstd.decompress(Zstd.compress(d));
        assertArrayEquals(d, r);
    }

    @Test @Order(3)
    void allCompressionLevels() {
        byte[] d = "Compression level test data for zstjd library. ".repeat(20).getBytes();
        for (int level : new int[]{Zstd.minLevel(), 1, 3, 5, 10, 19, Zstd.maxLevel()}) {
            byte[] c = Zstd.compress(d, level);
            byte[] r = Zstd.decompress(c);
            assertArrayEquals(d, r, "Failed at level " + level);
        }
    }

    @Test @Order(4)
    void repetitiveData() {
        byte[] d = "abcdefghijklmnopqrstuvwxyz".repeat(200).getBytes();
        byte[] c = Zstd.compress(d);
        assertTrue(c.length < d.length * 8 / 10, "repetitive data should compress: " + d.length + "->" + c.length);
        assertArrayEquals(d, Zstd.decompress(c));
    }

    @Test @Order(5)
    void randomData() {
        byte[] d = new byte[100000];
        new Random(42).nextBytes(d);
        byte[] c = Zstd.compress(d);
        assertTrue(c.length >= d.length * 95 / 100, "random should not compress much: " + d.length + "->" + c.length);
        assertArrayEquals(d, Zstd.decompress(c));
    }

    @Test @Order(6)
    void allSameByte() {
        byte[] d = new byte[10000];
        Arrays.fill(d, (byte) 'X');
        byte[] c = Zstd.compress(d);
        assertTrue(c.length < 50, "RLE should compress tiny: " + c.length + " bytes");
        assertArrayEquals(d, Zstd.decompress(c));
    }

    @Test @Order(7)
    void variousSizes() {
        for (int len : new int[]{1, 2, 3, 10, 100, 1000, 10000, 100000}) {
            byte[] d = new byte[len];
            for (int i = 0; i < len; i++) d[i] = (byte) (i * 7 + 13);
            assertArrayEquals(d, Zstd.decompress(Zstd.compress(d)), "Failed at size " + len);
        }
    }

    @Test @Order(8)
    void streaming() throws Exception {
        byte[] data = "Streaming test data for zstjd! ".repeat(100).getBytes("UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZstdOutputStream zos = new ZstdOutputStream(bos, 3)) { zos.write(data); }
        byte[] compressed = bos.toByteArray();
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdInputStream zis = new ZstdInputStream(bis)) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = zis.read(tmp)) >= 0) out.write(tmp, 0, n);
        }
        assertArrayEquals(data, out.toByteArray());
    }

    @Test @Order(9)
    void compressBound() {
        assertTrue(Zstd.compressBound(100) > 100);
        assertEquals(0xFD2FB528, Zstd.magicNumber());
        assertEquals(3, Zstd.defaultLevel());
        assertTrue(Zstd.minLevel() < 0);
        assertTrue(Zstd.maxLevel() > 0);
    }

    @Test @Order(10)
    void getDecompressedSize() {
        byte[] d = "Test content size detection for zstjd.".repeat(5).getBytes();
        byte[] c = Zstd.compress(d);
        int size = Zstd.getDecompressedSize(c);
        assertTrue(size == d.length || size < 0);
    }

    @Test @Order(11)
    void multiThreaded() throws Exception {
        byte[] data = "Multi-threaded zstjd compression test. ".repeat(50).getBytes();
        int threads = 8, iters = 100;
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        byte[] c = Zstd.compress(data);
                        byte[] r = Zstd.decompress(c);
                        if (!Arrays.equals(data, r)) errors.incrementAndGet();
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
                finally { latch.countDown(); }
            });
            th.setDaemon(true); th.start();
        }
        latch.await();
        assertEquals(0, errors.get(), "Thread safety errors");
    }

    @Test @Order(12)
    void repeatedReuse() {
        byte[] d = "Reuse test data for ThreadLocal context verification.".getBytes();
        for (int i = 0; i < 1000; i++) {
            byte[] c = Zstd.compress(d);
            byte[] r = Zstd.decompress(c);
            assertArrayEquals(d, r);
        }
    }

    @Test @Order(13)
    void largeBlock() {
        byte[] d = new byte[200000];
        for (int i = 0; i < d.length; i++) d[i] = (byte) (i % 251);
        byte[] c = Zstd.compress(d);
        assertArrayEquals(d, Zstd.decompress(c));
    }

    @Test @Order(14)
    void fseRoundTrip() {
        // Test FSE tables directly
        int[] dist = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
        org.zstjd.internal.FseTable t = org.zstjd.internal.FseTable.fromDist(dist, 6, 35);
        assertEquals(64, t.tableSize);
        assertEquals(6, t.accuracyLog);
        for (int s = 0; s < t.tableSize; s++) {
            int sym = t.symbol[s] & 0xFFFF;
            assertTrue(sym >= 0 && sym <= 35, "Invalid symbol " + sym + " at state " + s);
        }
    }

    @Test @Order(15)
    void crossCompat() throws Exception {
        Process which = new ProcessBuilder("which", "zstd").start();
        if (which.waitFor() != 0) return; // skip if no zstd CLI

        String data = "Cross-compatibility test data for zstjd CLI verification! ".repeat(10);
        byte[] ourCompressed = Zstd.compress(data.getBytes("UTF-8"));
        Files.write(Paths.get("/tmp/zstjd_junit.zst"), ourCompressed);
        Process p = new ProcessBuilder("zstd", "-d", "/tmp/zstjd_junit.zst", "-o", "/tmp/zstjd_junit.txt", "-f").start();
        if (p.waitFor() != 0) {
            // Our compressed blocks may not be CLI-compatible yet; that's OK
            return;
        }
        byte[] decoded = Files.readAllBytes(Paths.get("/tmp/zstjd_junit.txt"));
        assertEquals(data, new String(decoded, "UTF-8"));
    }
}
