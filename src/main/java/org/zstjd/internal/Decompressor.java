package org.zstjd.internal;

import java.util.Arrays;

public final class Decompressor {
    private byte[] dst;
    private int dstPos;

    public Decompressor() {
        dst = new byte[4096];
    }

    public void reset() {
        dstPos = 0;
    }

    public byte[] decompress(byte[] src) {
        return decompress(src, 0, src.length);
    }

    public byte[] decompress(byte[] src, int off, int len) {
        dstPos = 0;
        int pos = off;
        int end = off + len;
        int magic = Constants.readLE32(src, pos); pos += 4;
        if (magic != Constants.ZSTD_MAGIC)
            throw new IllegalArgumentException("Bad magic: 0x" + Integer.toHexString(magic));
        pos = parseFrameHeader(src, pos);
        while (pos + 3 <= end) {
            int bh = Constants.readLE24(src, pos); pos += 3;
            boolean last = (bh & 1) != 0;
            int type = (bh >> 1) & 3;
            int bSize = (bh >> 3) & 0x1FFFFF;
            if (bSize > off + len - pos) throw new IllegalArgumentException("Block too big");
            if (type == Constants.BLOCK_RAW) {
                grow(dstPos + bSize);
                System.arraycopy(src, pos, dst, dstPos, bSize);
                dstPos += bSize; pos += bSize;
            } else if (type == Constants.BLOCK_RLE) {
                byte val = src[pos]; pos++;
                grow(dstPos + bSize);
                Arrays.fill(dst, dstPos, dstPos + bSize, val);
                dstPos += bSize;
            } else if (type == Constants.BLOCK_COMPRESSED) {
                pos += parseCompressedBlock(src, pos, bSize);
            } else throw new IllegalArgumentException("Bad block type: " + type);
            if (last) break;
        }
        return Arrays.copyOf(dst, dstPos);
    }

    private int parseFrameHeader(byte[] src, int pos) {
        int fhd = src[pos] & 0xFF; pos++;
        int fcsId = (fhd >> 6) & 3;
        boolean single = ((fhd >> 5) & 1) != 0;
        int dictIdFlag = fhd & 3;
        if (!single) {
            int wlByte = src[pos] & 0xFF; pos++;
            long windowLog = (wlByte >> 3) + Constants.WINDOW_LOG_MIN + (wlByte & 7);
            int winSize = (int) Math.min(1L << Math.min(windowLog, Constants.WINDOW_LOG_MAX), 1 << 25);
            if (dst.length < winSize) dst = new byte[winSize];
        }
        if (fcsId == 1) { pos += 2; }
        else if (fcsId == 2) { pos += 4; }
        else if (fcsId == 3) { pos += 8; }
        if (dictIdFlag > 0) { pos += 1 << dictIdFlag; }
        return pos;
    }

    private int parseCompressedBlock(byte[] src, int pos, int bSize) {
        int start = pos;
        int lt = (src[pos] >> 2) & 3;
        int hdr = src[pos] & 0xFF; pos++;
        int regen = hdr >> 3;
        int stream1;
        if (regen < 32) {
            stream1 = src[pos] & 0xFF; pos++;
            regen = (regen << 4) | (stream1 >> 4);
            if (lt == Constants.LITERALS_RAW) {
                int rawSize = stream1 & 0xF;
                grow(dstPos + rawSize);
                System.arraycopy(src, pos, dst, dstPos, rawSize);
                dstPos += rawSize; pos += rawSize;
                return pos - start;
            }
        } else if (regen < 4096) {
            stream1 = Constants.readLE16(src, pos); pos += 2;
            regen = (regen << 4) | (stream1 >> 4);
            if (lt == Constants.LITERALS_RAW) {
                int rawSize = stream1 & 0xF;
                grow(dstPos + rawSize);
                System.arraycopy(src, pos, dst, dstPos, rawSize);
                dstPos += rawSize; pos += rawSize;
                return pos - start;
            }
        } else {
            stream1 = Constants.readLE32(src, pos) & 0xFFFFFF; pos += 3;
            regen = (regen << 4) | (stream1 >> 4);
            if (lt == Constants.LITERALS_RAW) {
                int rawSize = (stream1 >> 4) & 0xFFFFF;
                grow(dstPos + rawSize);
                System.arraycopy(src, pos, dst, dstPos, rawSize);
                dstPos += rawSize; pos += rawSize;
                return pos - start;
            }
        }
        if (lt == Constants.LITERALS_RLE) {
            byte val = src[pos]; pos++;
            grow(dstPos + regen);
            Arrays.fill(dst, dstPos, dstPos + regen, val);
            dstPos += regen;
            return pos - start;
        }
        return bSize; // Skip compressed blocks for now
    }

    public static int getContentSize(byte[] data) {
        if (data.length < 8) return Constants.CONTENTSIZE_ERROR;
        int magic = Constants.readLE32(data, 0);
        if (magic != Constants.ZSTD_MAGIC) return Constants.CONTENTSIZE_ERROR;
        int fhd = data[4] & 0xFF;
        int fcsId = (fhd >> 6) & 3;
        boolean single = ((fhd >> 5) & 1) != 0;
        int skip = 5;
        if (!single) skip++;
        if (fcsId == 1) {
            if (data.length < skip + 2) return Constants.CONTENTSIZE_UNKNOWN;
            return (Constants.readLE16(data, skip) & 0xFFFF) + 256;
        }
        if (fcsId == 2) {
            if (data.length < skip + 4) return Constants.CONTENTSIZE_UNKNOWN;
            return Constants.readLE32(data, skip);
        }
        if (fcsId == 3) {
            if (data.length < skip + 8) return Constants.CONTENTSIZE_UNKNOWN;
            return (int) Constants.readLE64(data, skip);
        }
        if (single && data.length > skip) {
            return data[skip] & 0xFF;
        }
        return Constants.CONTENTSIZE_UNKNOWN;
    }

    private void grow(int needed) {
        if (needed > dst.length) {
            int n = Math.max(needed, Math.max(dst.length * 2, 1 << 20));
            dst = Arrays.copyOf(dst, n);
        }
    }
}
