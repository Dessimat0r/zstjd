package org.zstjd;

import org.zstjd.internal.Compressor;
import org.zstjd.internal.Decompressor;
import org.zstjd.internal.Dict;
import org.zstjd.internal.Constants;
import java.util.Arrays;
import java.util.concurrent.*;

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

    public static byte[] compress(byte[] data, int level, boolean withContentSize) {
        Compressor c = TL_COMP.get();
        c.reset(level);
        return c.compress(data, withContentSize);
    }

    public static byte[] compress(byte[] data, int level, int workers) {
        if (workers <= 1 || data.length < 65536) return compress(data, level);
        int chunkSize = Math.max(Constants.BLOCK_SIZE_MAX, (data.length + workers - 1) / workers);
        chunkSize = Math.min(chunkSize, data.length);
        int numChunks = (data.length + chunkSize - 1) / chunkSize;

        byte[][] results = new byte[numChunks][];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<Future<Void>>(numChunks);
            for (int i = 0; i < numChunks; i++) {
                int idx = i;
                int off = idx * chunkSize;
                int len = Math.min(chunkSize, data.length - off);
                futures.add(executor.submit(() -> {
                    results[idx] = new Compressor(level).compress(Arrays.copyOfRange(data, off, off + len));
                    return null;
                }));
            }
            // Wait for all and propagate exceptions
            for (var f : futures) {
                try { f.get(); } catch (ExecutionException e) { throw new ZstdException(ZstdException.GENERIC, e.getCause().getMessage(), e.getCause()); }
                  catch (InterruptedException e) { throw new ZstdException(ZstdException.GENERIC, e.getMessage(), e); }
            }
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

    public static byte[] compress(byte[] data, int level, Dict dict) {
        Compressor c = TL_COMP.get();
        c.reset(level);
        return c.compress(data, dict);
    }

    public static byte[] compress(byte[] data, int level, boolean withContentSize, Dict dict) {
        Compressor c = TL_COMP.get();
        c.reset(level);
        return c.compress(data, withContentSize, dict);
    }

    public static byte[] decompress(byte[] data) {
        Decompressor d = TL_DECOMP.get();
        d.reset();
        return d.decompress(data);
    }

    public static byte[] decompress(byte[] data, Dict dict) {
        Decompressor d = TL_DECOMP.get();
        d.reset();
        d.useDict(dict);
        return d.decompress(data);
    }

    public static int decompress(byte[] data, byte[] output, int outputOffset) {
        Decompressor d = TL_DECOMP.get();
        d.reset();
        return d.decompress(data, 0, data.length, output, outputOffset);
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
