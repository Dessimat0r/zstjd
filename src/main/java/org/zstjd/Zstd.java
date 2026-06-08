package org.zstjd;

import org.zstjd.internal.Compressor;
import org.zstjd.internal.Decompressor;
import org.zstjd.internal.Constants;
import java.util.Arrays;

public class Zstd {

    private static final ThreadLocal<Compressor> TL_COMP = ThreadLocal.withInitial(() -> new Compressor(Constants.DEFAULT_LEVEL));
    private static final ThreadLocal<Decompressor> TL_DECOMP = ThreadLocal.withInitial(Decompressor::new);
    public static byte[] compress(byte[] data) {
        return compress(data, Constants.DEFAULT_LEVEL);
    }

    public static byte[] compress(byte[] data, int level) {
        Compressor c = TL_COMP.get();
        c.reset(level);
        return c.compress(data);
    }

    public static byte[] compress(byte[] data, int level, int workers) {
        if (workers <= 1 || data.length < 65536) return compress(data, level);
        int chunkSize = Math.max(Constants.BLOCK_SIZE_MAX, (data.length + workers - 1) / workers);
        chunkSize = Math.min(chunkSize, data.length);
        int numChunks = (data.length + chunkSize - 1) / chunkSize;

        byte[][] results = new byte[numChunks][];
        Thread[] threads = new Thread[numChunks - 1];
        int lastIdx = numChunks - 1;

        // Launch workers for all but last chunk
        for (int i = 0; i < lastIdx; i++) {
            int idx = i;
            int off = idx * chunkSize;
            int len = Math.min(chunkSize, data.length - off);
            Thread t = new Thread(() -> {
                results[idx] = new Compressor(level).compress(Arrays.copyOfRange(data, off, off + len));
            });
            threads[idx] = t;
            t.start();
        }
        // Compress last chunk in current thread
        int lastOff = lastIdx * chunkSize;
        int lastLen = Math.min(chunkSize, data.length - lastOff);
        results[lastIdx] = new Compressor(level).compress(Arrays.copyOfRange(data, lastOff, lastOff + lastLen));

        // Join all threads
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }

        int totalLen = 0;
        for (byte[] r : results) totalLen += r.length;
        byte[] out = new byte[totalLen];
        int pos = 0;
        for (byte[] r : results) {
            System.arraycopy(r, 0, out, pos, r.length);
            pos += r.length;
        }
        return out;
    }

    public static byte[] decompress(byte[] data) {
        Decompressor d = TL_DECOMP.get();
        d.reset();
        return d.decompress(data);
    }

    public static int getDecompressedSize(byte[] data) {
        return Decompressor.getContentSize(data);
    }

    public static long compressBound(long srcLen) {
        return Constants.compressBound(srcLen);
    }

    public static int magicNumber() { return Constants.ZSTD_MAGIC; }
    public static int defaultLevel() { return Constants.DEFAULT_LEVEL; }
    public static int minLevel() { return Constants.MIN_LEVEL; }
    public static int maxLevel() { return Constants.MAX_LEVEL; }
}
