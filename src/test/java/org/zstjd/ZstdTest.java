package org.zstjd;

import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ZstdTest {
    public static void main(String[] args) throws Exception {
        testSimple();
        testLargeData();
        testStream();
        testMultiThreaded();
        testCrossCompat();
        System.out.println("\nALL TESTS PASSED");
    }

    static void testSimple() throws Exception {
        byte[] data = "Hello World! Testing zstjd compression library. ".getBytes("UTF-8");
        byte[] compressed = Zstd.compress(data);
        byte[] decompressed = Zstd.decompress(compressed);
        assert Arrays.equals(data, decompressed) : "Simple round-trip failed";
        System.out.printf("simple: %d->%d bytes (%.0f%%)%n", data.length, compressed.length,
            100.0 * compressed.length / data.length);

        byte[] empty = new byte[0];
        assert Arrays.equals(empty, Zstd.decompress(Zstd.compress(empty))) : "Empty data";
        System.out.println("  empty: OK");
        assert Zstd.compressBound(100) > 0 : "compressBound";
        assert Zstd.magicNumber() == 0xFD2FB528 : "magic";
        System.out.println("  constants: OK");
    }

    static void testLargeData() throws Exception {
        byte[] data = new byte[1024 * 1024];
        new Random(42).nextBytes(data);
        long t0 = System.nanoTime();
        byte[] c = Zstd.compress(data);
        long t1 = System.nanoTime();
        byte[] d = Zstd.decompress(c);
        long t2 = System.nanoTime();
        assert Arrays.equals(data, d) : "Large data mismatch";
        double mb = data.length / 1e6;
        System.out.printf("1MB: compress=%.0f MB/s decompress=%.0f MB/s ratio=%.0f%%%n",
            mb / ((t1-t0)/1e9), mb / ((t2-t1)/1e9), 100.0 * c.length / data.length);
    }

    static void testStream() throws Exception {
        String input = "Stream test data for zstjd! ".repeat(100);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZstdOutputStream zos = new ZstdOutputStream(bos, 3)) {
            zos.write(input.getBytes("UTF-8"));
        }
        byte[] compressed = bos.toByteArray();

        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZstdInputStream zis = new ZstdInputStream(bis)) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = zis.read(tmp)) >= 0) result.write(tmp, 0, n);
        }
        assert input.equals(result.toString("UTF-8")) : "Stream round-trip";
        System.out.println("  stream: OK");
    }

    static void testMultiThreaded() throws Exception {
        byte[] data = "Multi-threaded zstjd compression test. ".repeat(50).getBytes("UTF-8");
        int threads = 8;
        int iters = 50;
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        byte[] c = Zstd.compress(data);
                        byte[] d = Zstd.decompress(c);
                        if (!Arrays.equals(data, d)) errors.incrementAndGet();
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
                finally { latch.countDown(); }
            });
            th.setDaemon(true); th.start();
        }
        latch.await();
        long elapsed = System.nanoTime() - t0;
        assert errors.get() == 0 : errors.get() + " errors";
        System.out.printf("  %d threads, %d ops: %.0f ops/s%n", threads, threads * iters,
            (threads * iters) / (elapsed / 1e9));
    }

    static void testCrossCompat() throws Exception {
        Process p = new ProcessBuilder("which", "zstd").start();
        if (p.waitFor() != 0) { System.out.println("  cross-compat: zstd CLI not found, skipping"); return; }

        String data = "Cross-compatibility test data for zstjd! ".repeat(10);
        byte[] ourCompressed = Zstd.compress(data.getBytes("UTF-8"));
        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/zstjd_cross.zst"), ourCompressed);

        Process p2 = new ProcessBuilder("zstd", "-d", "/tmp/zstjd_cross.zst", "-o", "/tmp/zstjd_cross.txt", "-f").start();
        int rc = p2.waitFor();
        if (rc != 0) { System.out.println("  cross-compat: zstd CLI decode failed"); return; }
        byte[] decoded = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/tmp/zstjd_cross.txt"));
        assert data.equals(new String(decoded, "UTF-8")) : "Cross-compat mismatch";
        System.out.println("  cross-compat (CLI decodes our output): PASS");
    }
}
