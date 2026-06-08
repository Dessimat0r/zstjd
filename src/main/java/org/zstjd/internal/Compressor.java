package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;
    private int dstPos;

    public Compressor(int level) { this.level = level; }
    public void reset(int level) { this.level = level; }

    public byte[] compress(byte[] src) {
        long maxOut = Constants.compressBound(src.length);
        if (maxOut <= 0) maxOut = src.length + 64;
        dst = new byte[(int) Math.min(maxOut, Integer.MAX_VALUE - 8)];
        dstPos = 0;
        writeFrameHeader();
        writeBlocks(src);
        return Arrays.copyOf(dst, dstPos);
    }

    private void writeFrameHeader() {
        Constants.writeLE32(dst, dstPos, Constants.ZSTD_MAGIC); dstPos += 4;
        dst[dstPos++] = 0;
        int wl = Math.max(17 + Math.min(8, level / 3), Constants.WINDOW_LOG_MIN);
        dst[dstPos++] = (byte)(((wl - Constants.WINDOW_LOG_MIN) & 0x1F) << 3);
    }

    private void ensure(int needed) {
        if (dstPos + needed > dst.length)
            dst = Arrays.copyOf(dst, Math.max(dst.length * 2, dstPos + needed));
    }

    private void writeBlocks(byte[] src) {
        int srcPos = 0, remaining = src.length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, Constants.BLOCK_SIZE_MAX);
            boolean last = (remaining == chunk);
            int blockSize = writeBestBlock(src, srcPos, chunk, last);
            srcPos += chunk;
            remaining -= chunk;
        }
    }

    private int writeBestBlock(byte[] src, int srcPos, int size, boolean last) {
        // Check RLE
        if (size > 0) {
            boolean allSame = true;
            for (int i = 1; i < size && allSame; i++)
                if (src[srcPos + i] != src[srcPos]) allSame = false;
            if (allSame) {
                ensure(7);
                int hdr = (last ? 1 : 0) | (Constants.BLOCK_RLE << 1) | (size << 3);
                Constants.writeLE24(dst, dstPos, hdr); dstPos += 3;
                dst[dstPos++] = src[srcPos];
                return size;
            }
        }
        // Raw block
        ensure(3 + size);
        int hdr = (last ? 1 : 0) | (Constants.BLOCK_RAW << 1) | (size << 3);
        Constants.writeLE24(dst, dstPos, hdr); dstPos += 3;
        System.arraycopy(src, srcPos, dst, dstPos, size);
        dstPos += size;
        return size;
    }
}
