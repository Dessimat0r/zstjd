package org.zstjd.internal;

import java.util.Arrays;

public final class Huff {
    private Huff() {}

    private static final int MAX_SYMBOLS = 256;
    private static final int MAX_TABLE_LOG = 12;
    private static final int MAX_TABLE_SIZE = 1 << MAX_TABLE_LOG;
    private static final int MAX_CODE_LEN = 11;

    // ---------- Encoding ----------

    public static int compress(byte[] src, int off, int len, byte[] dst, int dstOff) {
        if (len <= 0) return 0;

        // Count frequencies
        int[] freqs = new int[MAX_SYMBOLS];
        for (int i = 0; i < len; i++) freqs[src[off + i] & 0xFF]++;

        int numSymbols = 0;
        for (int f : freqs) if (f > 0) numSymbols++;

        // RLE case: all same byte
        if (numSymbols == 1) {
            for (int i = 0; i < MAX_SYMBOLS; i++) {
                if (freqs[i] > 0) {
                    dst[dstOff] = (byte)(i >> 8);
                    dst[dstOff + 1] = (byte)i;
                    return 2;
                }
            }
        }

        if (numSymbols == 0) return 0;

        // Build code lengths via simple iterative approach
        int[] codeLen = computeCodeLengths(freqs, MAX_CODE_LEN);
        if (codeLen == null) return 0;

        // Build encoding table
        int[] codeWord = new int[MAX_SYMBOLS];
        int[] codeBits = new int[MAX_SYMBOLS];
        buildEncodingTable(codeLen, codeWord, codeBits);

        // Write Huffman tree description (weights as 4-bit nibbles)
        int headerSize = writeHuffHeader(dst, dstOff, codeLen);

        // Encode literals into bitstream
        int bitstreamStart = dstOff + headerSize;
        int maxOut = dst.length - bitstreamStart;
        if (maxOut < 4) return 0;

        int pos = bitstreamStart;
        long container = 0;
        int bits = 0;

        for (int i = 0; i < len; i++) {
            int sym = src[off + i] & 0xFF;
            long code = codeWord[sym] & 0xFFFFL;
            int nb = codeBits[sym];
            container |= code << bits;
            bits += nb;
            while (bits >= 8) {
                if (pos >= dst.length) return 0;
                dst[pos++] = (byte)container;
                container >>>= 8;
                bits -= 8;
            }
        }
        // End mark
        container |= 1L << bits;
        bits++;
        while (bits > 0) {
            if (pos >= dst.length) return 0;
            dst[pos++] = (byte)container;
            container >>>= 8;
            bits -= 8;
        }

        int compSize = pos - bitstreamStart;
        int totalSize = headerSize + compSize;
        return totalSize;
    }

    private static int[] computeCodeLengths(int[] freqs, int maxBits) {
        int[] sorted = new int[MAX_SYMBOLS];
        int n = 0;
        for (int i = 0; i < MAX_SYMBOLS; i++) {
            if (freqs[i] > 0) sorted[n++] = freqs[i];
        }
        if (n <= 2) {
            int[] cl = new int[MAX_SYMBOLS];
            if (n == 1) {
                for (int i = 0; i < MAX_SYMBOLS; i++) cl[i] = freqs[i] > 0 ? 1 : 0;
            } else {
                int maxFreq = 0;
                for (int f : freqs) if (f > maxFreq) maxFreq = f;
                for (int i = 0; i < MAX_SYMBOLS; i++) {
                    cl[i] = freqs[i] == maxFreq ? 1 : (freqs[i] > 0 ? 2 : 0);
                }
            }
            return cl;
        }

        // Standard Huffman tree building
        int[] a = new int[n * 2];
        int[] parent = new int[n * 2];
        Arrays.fill(parent, -1);
        System.arraycopy(sorted, 0, a, 0, n);

        int remaining = n;
        int m = n;
        while (remaining > 1) {
            int s1 = -1, s2 = -1;
            for (int i = 0; i < m; i++) {
                if (a[i] != Integer.MAX_VALUE) {
                    if (s1 < 0 || a[i] < a[s1]) { s2 = s1; s1 = i; }
                    else if (s2 < 0 || a[i] < a[s2]) s2 = i;
                }
            }
            if (s2 < 0) break;
            a[m] = a[s1] + a[s2];
            parent[s1] = m; parent[s2] = m;
            a[s1] = Integer.MAX_VALUE;
            a[s2] = Integer.MAX_VALUE;
            m++;
            remaining--;
        }

        int[] depths = new int[n * 2];
        for (int i = m - 1; i >= 0; i--) {
            if (parent[i] >= 0) depths[i] = depths[parent[i]] + 1;
        }

        int[] codeLen = new int[MAX_SYMBOLS];
        int idx = 0;
        for (int i = 0; i < MAX_SYMBOLS; i++) {
            if (freqs[i] > 0) {
                int d = depths[idx++];
                codeLen[i] = Math.min(d, maxBits);
            }
        }

        // Ensure at least 2 bits difference between max and min if needed
        int maxLen = 0, minLen = maxBits;
        for (int i = 0; i < MAX_SYMBOLS; i++) {
            if (codeLen[i] > 0) {
                if (codeLen[i] > maxLen) maxLen = codeLen[i];
                if (codeLen[i] < minLen) minLen = codeLen[i];
            }
        }
        if (minLen == maxLen && minLen > 1) {
            for (int i = 0; i < MAX_SYMBOLS; i++) {
                if (codeLen[i] == maxLen) { codeLen[i]--; break; }
            }
        }

        return codeLen;
    }

    private static void buildEncodingTable(int[] codeLen, int[] codeWord, int[] codeBits) {
        int maxLen = 0;
        for (int cl : codeLen) if (cl > maxLen) maxLen = cl;
        int[] blCount = new int[maxLen + 1];
        for (int cl : codeLen) if (cl > 0) blCount[cl]++;
        int[] nextCode = new int[maxLen + 1];
        int code = 0;
        for (int i = 1; i <= maxLen; i++) {
            code = (code + blCount[i - 1]) << 1;
            nextCode[i] = code;
        }
        for (int sym = 0; sym < MAX_SYMBOLS; sym++) {
            int cl = codeLen[sym];
            if (cl > 0) {
                codeWord[sym] = nextCode[cl]++;
                codeBits[sym] = cl;
            }
        }
    }

    private static int writeHuffHeader(byte[] dst, int off, int[] codeLen) {
        int pos = off;
        // Find max symbol with non-zero code length
        int maxSym = 0;
        for (int i = MAX_SYMBOLS - 1; i >= 0; i--) {
            if (codeLen[i] > 0) { maxSym = i; break; }
        }
        // Count unique code lengths
        boolean[] seenLen = new boolean[MAX_CODE_LEN + 1];
        for (int cl : codeLen) if (cl > 0) seenLen[cl] = true;
        int numLens = 0;
        for (boolean s : seenLen) if (s) numLens++;

        if (numLens <= 1 && maxSym <= 1) {
            // Simple case: single weight for all
            dst[pos++] = (byte)(1 - 1);
            dst[pos++] = (byte)maxSym;
            for (int cl : codeLen) if (cl > 0) { dst[pos++] = (byte)cl; break; }
            return pos - off;
        }

        // Write header: number of symbols, then weights as 4-bit nibbles
        // The reference uses a compact weight representation
        // For simplicity, use 4-bit weights (nibbles)
        int weightCount = maxSym + 1;
        int headerBytes = (weightCount + 1) / 2 + 1; // +1 for weightCount byte

        // Write weight count
        dst[pos++] = (byte)(weightCount - 1);

        // Write weights as nibbles (each weight = codeLen, clamped to 0-15)
        int nibblePos = 0;
        for (int i = 0; i <= maxSym; i++) {
            int w = codeLen[i] > 0 ? codeLen[i] : 0;
            if (nibblePos % 2 == 0) {
                dst[pos] = (byte)(w & 0xF);
            } else {
                dst[pos] |= (byte)((w & 0xF) << 4);
                pos++;
            }
            nibblePos++;
        }
        if (nibblePos % 2 != 0) pos++;

        return pos - off;
    }

    // ---------- Decoding ----------

    public static int decompress(byte[] src, int off, int size, byte[] dst, int dstOff, int expected) {
        if (size <= 0 || expected <= 0) return 0;

        int pos = off;

        // Read header
        int weightCount = (src[pos++] & 0xFF) + 1;
        if (weightCount > MAX_SYMBOLS) return 0;

        int[] codeLen = new int[MAX_SYMBOLS];
        int nibblePos = 0;
        for (int i = 0; i < weightCount; i++) {
            int w;
            if (nibblePos % 2 == 0) {
                w = src[pos] & 0xF;
            } else {
                w = (src[pos] >> 4) & 0xF;
                pos++;
            }
            nibblePos++;
            codeLen[i] = w;
        }
        if (nibblePos % 2 != 0) pos++;
        if (nibblePos < weightCount + 1) pos = Math.min(pos + 1, off + size);

        int bitstreamOff = pos;
        int bitstreamSize = size - (bitstreamOff - off);
        if (bitstreamSize <= 0) return 0;

        // Build decoding table
        short[] decodeTable = new short[MAX_TABLE_SIZE];
        byte[] decodeNbBits = new byte[MAX_TABLE_SIZE];
        int tableLog = buildDecodingTable(codeLen, decodeTable, decodeNbBits);

        if (tableLog <= 0) {
            // Try RLE
            int sym = 0;
            for (int i = 0; i < MAX_SYMBOLS; i++) if (codeLen[i] > 0) { sym = i; break; }
            Arrays.fill(dst, dstOff, dstOff + expected, (byte)sym);
            return size;
        }

        // Decode bitstream
        long container = 0;
        int bits = 0;
        int writeIdx = dstOff;
        int readPos = bitstreamOff;
        int end = off + size;

        while (writeIdx < dstOff + expected && readPos <= end) {
            // Refill container
            while (bits <= 56 && readPos < end) {
                container |= (long)(src[readPos++] & 0xFF) << bits;
                bits += 8;
            }
            if (bits <= 0) break;

            int entry = decodeTable[(int)(container & (MAX_TABLE_SIZE - 1))] & 0xFFFF;
            int sym = entry & 0xFF;
            int nbBits = (entry >> 8) & 0xFF;
            if (nbBits <= 0 || nbBits > bits) break;
            dst[writeIdx++] = (byte)sym;
            container >>>= nbBits;
            bits -= nbBits;
        }

        return writeIdx - dstOff;
    }

    private static int buildDecodingTable(int[] codeLen, short[] table, byte[] nbBits) {
        int maxLen = 0;
        for (int cl : codeLen) if (cl > maxLen) maxLen = cl;
        if (maxLen <= 0) return 0;
        int tableLog = Math.min(maxLen, MAX_TABLE_LOG);
        int tableSize = 1 << tableLog;

        int[] blCount = new int[maxLen + 1];
        for (int cl : codeLen) if (cl > 0) blCount[cl]++;

        int[] nextCode = new int[maxLen + 1];
        int code = 0;
        for (int i = 1; i <= maxLen; i++) {
            code = (code + blCount[i - 1]) << 1;
            nextCode[i] = code;
        }

        Arrays.fill(table, (short)-1);
        for (int sym = 0; sym < MAX_SYMBOLS; sym++) {
            int cl = codeLen[sym];
            if (cl == 0) continue;
            int start = nextCode[cl]++;
            int step = 1 << (tableLog - cl);
            for (int j = 0; j < step; j++) {
                int idx = start + (j << cl);
                if (idx < tableSize) {
                    table[idx] = (short)(sym | (cl << 8));
                    nbBits[idx] = (byte)cl;
                }
            }
        }
        return tableLog;
    }

    public static int getCompressedSize(byte[] src, int off, int size, byte[] dst, int dstOff) {
        return compress(src, off, size, dst, dstOff);
    }
}
