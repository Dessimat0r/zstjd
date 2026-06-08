package org.zstjd;

import org.zstjd.internal.Compressor;
import org.zstjd.internal.Decompressor;
import org.zstjd.internal.Constants;

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
