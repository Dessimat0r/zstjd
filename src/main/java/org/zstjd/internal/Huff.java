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
        return compressBody(src, off, len, dst, dstOff, false);
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

    // ---------- Treeless / table reuse ----------

    public static int[] computeCodeLengths2(byte[] src, int off, int len, int maxBits) {
        if (len <= 0) return null;
        int[] freqs = new int[MAX_SYMBOLS];
        for (int i = 0; i < len; i++) freqs[src[off + i] & 0xFF]++;
        return computeCodeLengths(freqs, maxBits);
    }

    public static int compressTreeless(byte[] src, int off, int len, byte[] dst, int dstOff) {
        return compressBody(src, off, len, dst, dstOff, true);
    }

    private static int compressBody(byte[] src, int off, int len, byte[] dst, int dstOff, boolean treeless) {
        if (len <= 0) return 0;
        int[] freqs = new int[MAX_SYMBOLS];
        for (int i = 0; i < len; i++) freqs[src[off + i] & 0xFF]++;
        int[] codeLen = computeCodeLengths(freqs, MAX_CODE_LEN);
        if (codeLen == null) return 0;
        return compressWithTable(src, off, len, dst, dstOff, codeLen, treeless);
    }

    private static int compressWithTable(byte[] src, int off, int len, byte[] dst, int dstOff, int[] codeLen, boolean treeless) {
        int[] codeWord = new int[MAX_SYMBOLS];
        int[] codeBits = new int[MAX_SYMBOLS];
        buildEncodingTable(codeLen, codeWord, codeBits);
        int headerSize = treeless ? 0 : writeHuffHeader(dst, dstOff, codeLen);
        int bodyOff = dstOff + headerSize;
        int bodySize;
        if (len > 1024) {
            bodySize = compress4(src, off, len, dst, bodyOff, codeWord, codeBits);
        } else {
            bodySize = writeBitstream(src, off, len, dst, bodyOff, codeWord, codeBits);
        }
        return headerSize + bodySize;
    }

    private static int writeBitstream(byte[] src, int off, int len, byte[] dst, int dstOff, int[] codeWord, int[] codeBits) {
        return bitstreamSize(src, off, len, dst, dstOff, codeWord, codeBits);
    }

    private static int bitstreamSize(byte[] src, int off, int len, byte[] dst, int dstOff, int[] codeWord, int[] codeBits) {
        int pos = dstOff;
        long container = 0;
        int bits = 0;
        int maxOut = dst.length - dstOff;
        for (int i = 0; i < len; i++) {
            int sym = src[off + i] & 0xFF;
            container |= (codeWord[sym] & 0xFFFFL) << bits;
            bits += codeBits[sym];
            while (bits >= 8) {
                if (pos - dstOff >= maxOut) return 0;
                dst[pos++] = (byte)container;
                container >>>= 8;
                bits -= 8;
            }
        }
        container |= 1L << bits;
        bits++;
        while (bits > 0) {
            if (pos - dstOff >= maxOut) return 0;
            dst[pos++] = (byte)container;
            container >>>= 8;
            bits -= 8;
        }
        return pos - dstOff;
    }

    // 4-stream variants
    private static int compress4(byte[] src, int off, int len, byte[] dst, int dstOff, int[] codeWord, int[] codeBits) {
        // Split input into 4 roughly equal segments
        int segLen = (len + 3) / 4;
        int[] segSizes = new int[4];
        int totalComp = 0;
        int writePos = dstOff;
        // Reserve space for jump table (4 × 2 bytes)
        int jumpOff = writePos;
        writePos += 8;
        for (int i = 0; i < 4; i++) {
            int segOff = off + Math.min(i * segLen, len);
            int segEnd = Math.min((i + 1) * segLen, len);
            int segSz = segEnd - (segOff - off);
            if (segSz <= 0) { segSizes[i] = 0; continue; }
            int sz = bitstreamSize(src, segOff, segSz, dst, writePos, codeWord, codeBits);
            segSizes[i] = sz;
            writePos += sz;
            totalComp += sz;
        }
        // Write jump table (little-endian 16-bit sizes)
        for (int i = 0; i < 4; i++) {
            dst[jumpOff + i * 2] = (byte)(segSizes[i] & 0xFF);
            dst[jumpOff + i * 2 + 1] = (byte)((segSizes[i] >> 8) & 0xFF);
        }
        return 8 + totalComp;
    }

    public static int decodeStream4(byte[] src, int off, int size, byte[] dst, int dstOff, int expected,
                                      short[] decodeTable, byte[] decodeNbBits, int tableLog) {
        if (size < 8) return 0;
        int tableMask = (1 << tableLog) - 1;
        // Read jump table
        int[] streamOff = new int[4];
        int[] streamSize = new int[4];
        int pos = off;
        for (int i = 0; i < 4; i++) {
            int sz = (src[pos] & 0xFF) | ((src[pos + 1] & 0xFF) << 8);
            streamSize[i] = sz;
            pos += 2;
        }
        for (int i = 0; i < 4; i++) {
            streamOff[i] = pos;
            pos += streamSize[i];
        }

        // 4 containers for 4 streams
        long[] containers = new long[4];
        int[] bits = new int[4];
        int[] readPos = new int[4];
        for (int i = 0; i < 4; i++) {
            readPos[i] = streamOff[i];
        }

        int writeIdx = dstOff;
        while (writeIdx < dstOff + expected) {
            for (int s = 0; s < 4; s++) {
                if (writeIdx >= dstOff + expected) break;
                // Refill container
                while (bits[s] <= 56 && readPos[s] < streamOff[s] + streamSize[s]) {
                    containers[s] |= (long)(src[readPos[s]++] & 0xFF) << bits[s];
                    bits[s] += 8;
                }
                if (bits[s] <= 0) continue;

                int entry = decodeTable[(int)(containers[s] & tableMask)] & 0xFFFF;
                int sym = entry & 0xFF;
                int nbBits = (entry >> 8) & 0xFF;
                if (nbBits <= 0 || nbBits > bits[s]) break;
                dst[writeIdx++] = (byte)sym;
                containers[s] >>>= nbBits;
                bits[s] -= nbBits;
            }
        }
        return writeIdx - dstOff;
    }

    // ---------- Decoding ----------

    public static int readHuffHeader(byte[] src, int off, int size, int[] codeLen) {
        int pos = off;
        int weightCount = (src[pos++] & 0xFF) + 1;
        if (weightCount > MAX_SYMBOLS) return -1;

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
        return pos - off;
    }

    public static int buildTable(int[] codeLen, short[] decodeTable, byte[] decodeNbBits) {
        return buildDecodingTable(codeLen, decodeTable, decodeNbBits);
    }

    public static int decompress(byte[] src, int off, int size, byte[] dst, int dstOff, int expected) {
        if (size <= 0 || expected <= 0) return 0;

        int[] codeLen = new int[MAX_SYMBOLS];
        int hdrSize = readHuffHeader(src, off, size, codeLen);
        if (hdrSize < 0) return 0;

        int bitstreamOff = off + hdrSize;
        int bitstreamSize = size - hdrSize;
        if (bitstreamSize <= 0) return 0;

        short[] decodeTable = new short[MAX_TABLE_SIZE];
        byte[] decodeNbBits = new byte[MAX_TABLE_SIZE];
        int tableLog = buildDecodingTable(codeLen, decodeTable, decodeNbBits);

        if (tableLog <= 0) {
            int sym = 0;
            for (int i = 0; i < MAX_SYMBOLS; i++) if (codeLen[i] > 0) { sym = i; break; }
            Arrays.fill(dst, dstOff, dstOff + expected, (byte)sym);
            return expected;
        }

        int decoded = decodeStream(src, bitstreamOff, bitstreamSize, dst, dstOff, expected, decodeTable, decodeNbBits, tableLog);
        return decoded;
    }

    public static int decodeStream(byte[] src, int off, int size, byte[] dst, int dstOff, int expected,
                                     short[] decodeTable, byte[] decodeNbBits, int tableLog) {
        int tableMask = (1 << tableLog) - 1;
        long container = 0;
        int bits = 0;
        int writeIdx = dstOff;
        int readPos = off;
        int end = off + size;

        while (writeIdx < dstOff + expected && readPos <= end) {
            while (bits <= 56 && readPos < end) {
                container |= (long)(src[readPos++] & 0xFF) << bits;
                bits += 8;
            }
            if (bits <= 0) break;

            int entry = decodeTable[(int)(container & tableMask)] & 0xFFFF;
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
