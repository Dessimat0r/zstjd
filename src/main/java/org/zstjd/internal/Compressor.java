package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;
    private int dstPos;

    private static final int[] LL_DIST = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    private static final int[] OF_DIST = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};
    private static final int[] ML_DIST = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
    private static final FseEncoder LL_TABLE, OF_TABLE, ML_TABLE;
    static {
        LL_TABLE = new FseEncoder(6, 35); LL_TABLE.init(LL_DIST, 35);
        OF_TABLE = new FseEncoder(5, 30); OF_TABLE.init(OF_DIST, 28);
        ML_TABLE = new FseEncoder(6, 52); ML_TABLE.init(ML_DIST, 52);
    }

    private static final int HASH_LOG = 14, HASH_SIZE = 1 << HASH_LOG, MIN_MATCH = 4, MAX_OFFSET = 1 << 18;

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

            int compSize = 0; // disabled - FSE encoding needs more work
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
        while (pos <= size - MIN_MATCH) {
            int h = hash4(src, base + pos) & (HASH_SIZE - 1);
            int match = hashTable[h];
            hashTable[h] = pos;
            if (match >= 0 && pos - match <= MAX_OFFSET) {
                int len = MIN_MATCH;
                while (len < 131072 && pos + len < size && src[base + match + len] == src[base + pos + len]) len++;
                if (len >= MIN_MATCH) {
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

        // Build decoding tables to find initial states
        FseTable llDec = FseTable.fromDist(LL_DIST, 6, 35);
        FseTable ofDec = FseTable.fromDist(OF_DIST, 5, 28);
        FseTable mlDec = FseTable.fromDist(ML_DIST, 6, 52);

        BitStream stream = new BitStream(dst, dstPos);
        int streamStart = dstPos;
        int ls = seqCount - 1;

        // Find initial states: any state that decodes to our symbol
        int mlS = findState(mlDec, mlCodes[ls] & 0xFF);
        int ofS = findState(ofDec, ofCodes[ls] & 0xFF);
        int llS = findState(llDec, litCodes[ls] & 0xFF);

        // Write extra bits (litLen, matchLen, offset) in forward order
        stream.writeBits(litLens[ls] - Constants.LITLEN_BASE[litCodes[ls] & 0xFF], Constants.LITLEN_BITS[litCodes[ls] & 0xFF]);
        stream.writeBits(matchLens[ls] - Constants.MATCHLEN_BASE[mlCodes[ls] & 0xFF], Constants.MATCHLEN_BITS[mlCodes[ls] & 0xFF]);
        int storedOffL = offs[ls] + 3;
        stream.writeBits(storedOffL - Constants.OFFSET_BASE[ofCodes[ls] & 0xFF], Constants.OFFSET_BITS[ofCodes[ls] & 0xFF]);
        stream.flush();

        // For multi-sequence, encode previous sequences
        for (int s = seqCount - 2; s >= 0; s--) {
            int llc = litCodes[s] & 0xFF, ofc = ofCodes[s] & 0xFF, mlc = mlCodes[s] & 0xFF;
            // Write state update bits for OF, ML, LL (reverse order for backward reading)
            stream.writeBits(ofS, ofDec.numBits[ofS] & 0xFF);
            stream.writeBits(mlS, mlDec.numBits[mlS] & 0xFF);
            stream.writeBits(llS, llDec.numBits[llS] & 0xFF);
            // Update states
            ofS = (ofDec.newState[ofS] & 0xFFFF) + 0; // no extra bits for state update
            mlS = (mlDec.newState[mlS] & 0xFFFF) + 0;
            llS = (llDec.newState[llS] & 0xFFFF) + 0;
            // Write extra bits
            stream.writeBits(litLens[s] - Constants.LITLEN_BASE[llc], Constants.LITLEN_BITS[llc]);
            stream.writeBits(matchLens[s] - Constants.MATCHLEN_BASE[mlc], Constants.MATCHLEN_BITS[mlc]);
            int storedOffM = offs[s] + 3;
            stream.writeBits(storedOffM - Constants.OFFSET_BASE[ofc], Constants.OFFSET_BITS[ofc]);
            stream.flush();
        }

        // Write initial states (at the END for backward reading order: LL, OF, ML)
        stream.writeBits(mlS, mlDec.accuracyLog);
        stream.writeBits(ofS, ofDec.accuracyLog);
        stream.writeBits(llS, llDec.accuracyLog);
        dstPos = streamStart + stream.close();
        int t = dstPos - outStart;
        return t >= size + 4 ? 0 : t;
    }

    private static int findState(FseTable t, int sym) {
        for (int i = 0; i < t.tableSize; i++)
            if ((t.symbol[i] & 0xFFFF) == sym) return i;
        return 0;
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
