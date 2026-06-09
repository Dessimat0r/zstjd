package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;
    private int dstPos;

    private static final int[] LL_DIST = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    private static final int[] OF_DIST = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};
    private static final int[] ML_DIST = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
    private static final FseEncoder LL_TABLE = new FseEncoder(FseTable.fromDist(LL_DIST, 6, 35));
    private static final FseEncoder OF_TABLE = new FseEncoder(FseTable.fromDist(OF_DIST, 5, 28));
    private static final FseEncoder ML_TABLE = new FseEncoder(FseTable.fromDist(ML_DIST, 6, 52));
    private static final FseTable LL_DEC = FseTable.fromDist(LL_DIST, 6, 35);
    private static final FseTable OF_DEC = FseTable.fromDist(OF_DIST, 5, 28);
    private static final FseTable ML_DEC = FseTable.fromDist(ML_DIST, 6, 52);

    private static final int HASH_LOG = 14, HASH_SIZE = 1 << HASH_LOG, MIN_MATCH = 4, MAX_OFFSET = 1 << 18;
    // Minimum match length for LZ77; lower = better compression ratio but higher false-match risk from hash collisions
    private static final int MIN_MATCH_LEN = 8;

    public Compressor(int level) { this.level = level; }
    public void reset(int level) { this.level = level; }

    public byte[] compress(byte[] src) {
        long maxOut = Constants.compressBound(src.length);
        if (maxOut <= 0) maxOut = src.length + 64;
        dst = new byte[(int) Math.min(maxOut, Integer.MAX_VALUE - 8)];
        dstPos = 0;
        Constants.writeLE32(dst, dstPos, Constants.ZSTD_MAGIC); dstPos += 4;
        dst[dstPos++] = 0;
        int wl = Math.max(17 + Math.min(8, level / 3), Constants.WINDOW_LOG_MIN);
        dst[dstPos++] = (byte)(((wl - Constants.WINDOW_LOG_MIN) & 0x1F) << 3);
        writeBlocks(src);
        return Arrays.copyOf(dst, dstPos);
    }

    private void ensure(int n) {
        if (dstPos + n > dst.length)
            dst = Arrays.copyOf(dst, Math.max(dst.length * 2, dstPos + n));
    }

    private void writeBlocks(byte[] src) {
        int srcPos = 0, remaining = src.length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, Constants.BLOCK_SIZE_MAX);
            boolean last = (remaining == chunk);
            int hdrPos = dstPos; dstPos += 3;
            int dataStart = dstPos;

            int compSize = tryLz77(src, srcPos, chunk);
            if (compSize > 0 && compSize < chunk * 8 / 10) {
                Constants.writeLE24(dst, hdrPos, (last ? 1 : 0) | (Constants.BLOCK_COMPRESSED << 1) | (compSize << 3));
                dstPos = dataStart + compSize;
            } else {
                dstPos = dataStart;
                if (chunk > 0) {
                    boolean allSame = true;
                    for (int i = 1; i < chunk && allSame; i++)
                        if (src[srcPos + i] != src[srcPos]) allSame = false;
                    if (allSame) {
                        Constants.writeLE24(dst, hdrPos, (last ? 1 : 0) | (Constants.BLOCK_RLE << 1) | (chunk << 3));
                        dst[dstPos++] = src[srcPos];
                    } else {
                        ensure(chunk);
                        System.arraycopy(src, srcPos, dst, dstPos, chunk);
                        Constants.writeLE24(dst, hdrPos, (last ? 1 : 0) | (Constants.BLOCK_RAW << 1) | (chunk << 3));
                        dstPos += chunk;
                    }
                }
            }
            srcPos += chunk;
            remaining -= chunk;
        }
    }

    private int tryLz77(byte[] src, int base, int size) {
        if (size < 32) return 0;
        // Quick entropy check: if too many unique bytes in first 64 bytes, skip
        int check = Math.min(size, 64);
        int unique = 0;
        boolean[] seen = new boolean[256];
        for (int i = 0; i < check; i++) { int b = src[base + i] & 0xFF; if (!seen[b]) { seen[b] = true; unique++; } }
        if (unique > Math.max(check / 2, 32)) return 0;

        int[] hashTable = new int[HASH_SIZE];
        Arrays.fill(hashTable, -1);

        int seqCount = 0, totalLits = 0;
        int[] litLens = new int[size / 4 + 10], offs = new int[size / 4 + 10], matchLens = new int[size / 4 + 10];
        byte[] litCodes = new byte[size / 4 + 10], ofCodes = new byte[size / 4 + 10], mlCodes = new byte[size / 4 + 10];

        int pos = 0, lastPos = 0;
        while (pos <= size - MIN_MATCH_LEN) {
            int h = hash4(src, base + pos) & (HASH_SIZE - 1);
            int match = hashTable[h];
            hashTable[h] = pos;
            if (match >= 0 && pos - match <= MAX_OFFSET) {
                int len = MIN_MATCH;
                while (len < 131072 && pos + len < size && src[base + match + len] == src[base + pos + len]) len++;
                if (len >= MIN_MATCH_LEN) {
                    int litLen = pos - lastPos;
                    int ofCode = offsetCode(pos - match);
                    litLens[seqCount] = litLen; offs[seqCount] = pos - match; matchLens[seqCount] = len;
                    litCodes[seqCount] = litLenCode(litLen); ofCodes[seqCount] = (byte)ofCode; mlCodes[seqCount] = (byte)matchLenCode(len);
                    totalLits += litLen; seqCount++;
                    pos += len; lastPos = pos; continue;
                }
            }
            pos++;
        }

        int trailing = size - lastPos;
        if (trailing > 0 || seqCount == 0) {
            litLens[seqCount] = trailing; offs[seqCount] = 0; matchLens[seqCount] = 0;
            litCodes[seqCount] = litLenCode(trailing); ofCodes[seqCount] = 0; mlCodes[seqCount] = 0;
            totalLits += trailing; seqCount++;
        }
        if (seqCount == 0) return 0;

        int outStart = dstPos;
        ensure(2048 + totalLits);

        // Write raw literals: bits 1-0 = block type (0=raw), bits 3-2 = size encoding type
        if (totalLits < 32) {
            dst[dstPos++] = (byte) ((0 << 2) | 0 | (totalLits << 3));
        } else if (totalLits < 4096) {
            int val = (1 << 2) | 0 | (totalLits << 4);
            dst[dstPos++] = (byte)val; dst[dstPos++] = (byte)(val >> 8);
        } else {
            int val = (3 << 2) | 0 | (totalLits << 4);
            dst[dstPos++] = (byte)val; dst[dstPos++] = (byte)(val >> 8); dst[dstPos++] = (byte)(val >> 16);
        }
        if (totalLits > 0) {
            System.arraycopy(src, base, dst, dstPos, totalLits);
            dstPos += totalLits;
        }

        dst[dstPos++] = seqCount < 0x7F ? (byte)seqCount : (byte)((seqCount >> 8) | 0x80);
        if (seqCount >= 0x7F) {
            if (seqCount < 0x7F00) dst[dstPos++] = (byte)seqCount;
            else { dst[dstPos - 1] = (byte)0xFF; dst[dstPos++] = (byte)(seqCount - 0x7F00); dst[dstPos++] = (byte)((seqCount - 0x7F00) >> 8); }
        }
        dst[dstPos++] = 0;

        BitStream stream = new BitStream(dst, dstPos);
        int streamStart = dstPos;
        int ls = seqCount - 1;

        int llCode = litCodes[ls] & 0xFF, ofCode = ofCodes[ls] & 0xFF, mlCode = mlCodes[ls] & 0xFF;
        int mlState = ML_TABLE.begin(mlCode);
        int ofState = OF_TABLE.begin(ofCode);
        int llState = LL_TABLE.begin(llCode);

        // Extra bits for last sequence (LSB-first per reference spec)
        stream.writeBits(litLens[ls] - Constants.LITLEN_BASE[llCode], Constants.LITLEN_BITS[llCode]);
        stream.writeBits(matchLens[ls] - Constants.MATCHLEN_BASE[mlCode], Constants.MATCHLEN_BITS[mlCode]);
        stream.writeBits(offs[ls] + 3 - Constants.OFFSET_BASE[ofCode], Constants.OFFSET_BITS[ofCode]);
        stream.flush();

        // Encode previous sequences in reverse order
        for (int s = seqCount - 2; s >= 0; s--) {
            int llc = litCodes[s] & 0xFF, ofc = ofCodes[s] & 0xFF, mlc = mlCodes[s] & 0xFF;
            ofState = OF_TABLE.encode(stream, ofState, ofc);
            mlState = ML_TABLE.encode(stream, mlState, mlc);
            llState = LL_TABLE.encode(stream, llState, llc);
            stream.writeBits(litLens[s] - Constants.LITLEN_BASE[llc], Constants.LITLEN_BITS[llc]);
            stream.writeBits(matchLens[s] - Constants.MATCHLEN_BASE[mlc], Constants.MATCHLEN_BITS[mlc]);
            stream.writeBits(offs[s] + 3 - Constants.OFFSET_BASE[ofc], Constants.OFFSET_BITS[ofc]);
            stream.flush();
        }

        // Write initial states (each finish flushes)
        ML_TABLE.finish(stream, mlState);
        OF_TABLE.finish(stream, ofState);
        LL_TABLE.finish(stream, llState);
        dstPos = streamStart + stream.close();
        int t = dstPos - outStart;
        return t >= size + 4 ? 0 : t;
    }

    private static int hash4(byte[] d, int p) {
        int h = (d[p]&0xFF)|((d[p+1]&0xFF)<<8)|((d[p+2]&0xFF)<<16);
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13; return h;
    }
    private static int offsetCode(int off) {
        if (off <= 0) return 0;
        int stored = off + 3;
        return Math.min(31 - Integer.numberOfLeadingZeros(stored), 30);
    }
    private static int matchLenCode(int len) { for (int i = Constants.MATCHLEN_BASE.length - 1; i >= 0; i--) if (Constants.MATCHLEN_BASE[i] <= len) return i; return 0; }
    private static byte litLenCode(int len) { for (int i = Constants.LITLEN_BASE.length - 1; i >= 0; i--) if (Constants.LITLEN_BASE[i] <= len) return (byte)i; return 0; }
}
