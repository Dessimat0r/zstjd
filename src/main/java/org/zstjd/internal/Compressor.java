package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;
    private int dstPos;

    private static final int[] LL_DIST = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    private static final int[] OF_DIST = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};
    private static final int[] ML_DIST = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1,-1,-1};

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
            int blockStart = dstPos;
            dstPos += 3; // placeholder for block header
            int dataStart = dstPos;

            // Try compressed, fall back to raw
            int compressedLen = tryCompressBlock(src, srcPos, chunk);

            if (compressedLen > 0 && compressedLen < chunk) {
                int hdr = (last ? 1 : 0) | (Constants.BLOCK_COMPRESSED << 1) | (compressedLen << 3);
                Constants.writeLE24(dst, blockStart, hdr);
                dstPos = dataStart + compressedLen;
            } else {
                dstPos = dataStart;
                ensure(chunk);
                System.arraycopy(src, srcPos, dst, dstPos, chunk);
                int hdr = (last ? 1 : 0) | (Constants.BLOCK_RAW << 1) | (chunk << 3);
                Constants.writeLE24(dst, blockStart, hdr);
                dstPos += chunk;
            }
            srcPos += chunk;
            remaining -= chunk;
        }
    }

    private int tryCompressBlock(byte[] src, int srcPos, int size) {
        return 0; // placeholder - compressed block encoding not yet implemented
    }
}
