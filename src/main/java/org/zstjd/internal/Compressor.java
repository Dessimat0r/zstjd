package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;

    public Compressor(int level) {
        this.level = Math.max(Constants.MIN_LEVEL, Math.min(Constants.MAX_LEVEL, level));
    }

    public void reset(int level) {
        this.level = level;
    }

    public byte[] compress(byte[] src) {
        long maxOut = Constants.compressBound(src.length);
        if (maxOut <= 0) maxOut = src.length + 64;
        dst = new byte[(int) Math.min(maxOut, Integer.MAX_VALUE - 8)];
        int pos = 0;
        pos = writeFrameHeader(pos);
        int srcPos = 0;
        while (srcPos < src.length) {
            int chunk = Math.min(src.length - srcPos, Constants.BLOCK_SIZE_MAX);
            boolean last = (srcPos + chunk >= src.length);
            boolean allSame = chunk > 1;
            if (allSame) {
                byte first = src[srcPos];
                for (int i = 1; i < chunk; i++) {
                    if (src[srcPos + i] != first) { allSame = false; break; }
                }
            }
            if (allSame) {
                int raw = (last ? 1 : 0) | (Constants.BLOCK_RLE << 1) | (chunk << 3);
                Constants.writeLE24(dst, pos, raw); pos += 3;
                dst[pos++] = src[srcPos];
            } else {
                int raw = (last ? 1 : 0) | (Constants.BLOCK_RAW << 1) | (chunk << 3);
                Constants.writeLE24(dst, pos, raw); pos += 3;
                System.arraycopy(src, srcPos, dst, pos, chunk);
                pos += chunk;
            }
            srcPos += chunk;
        }
        return Arrays.copyOf(dst, pos);
    }

    private int writeFrameHeader(int pos) {
        Constants.writeLE32(dst, pos, Constants.ZSTD_MAGIC); pos += 4;
        dst[pos++] = 0; // descriptor: no checksum, no dict, no content size, multi-segment
        int wLog = 17 + Math.min(8, level / 3);
        wLog = Math.max(wLog, Constants.WINDOW_LOG_MIN);
        dst[pos++] = (byte) (((wLog - Constants.WINDOW_LOG_MIN) & 0x1F) << 3);
        return pos;
    }
}
