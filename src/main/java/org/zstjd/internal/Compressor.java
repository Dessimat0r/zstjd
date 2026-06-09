package org.zstjd.internal;

import java.util.Arrays;

public final class Compressor {
    private int level;
    private byte[] dst;
    private int dstPos;
    private int[] hashTable = new int[1 << 14];
    private int[] chainNext = new int[Constants.BLOCK_SIZE_MAX];
    private int[] litLens, offs, matchLens;
    private byte[] litCodes, ofCodes, ofExtra, mlCodes;
    private long[] prevOff = {1, 4, 8};
    private boolean[] entropySeen = new boolean[256];
    private int[] huffCodeLen; // last Huffman tree for treeless reuse

    private static final int[] LL_DIST = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    private static final int[] OF_DIST = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};
    private static final int[] ML_DIST = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1};
    private static final FseEncoder LL_TABLE = new FseEncoder(FseTable.fromDist(LL_DIST, 6, 35));
    private static final FseEncoder OF_TABLE = new FseEncoder(FseTable.fromDist(OF_DIST, 5, 28));
    private static final FseEncoder ML_TABLE = new FseEncoder(FseTable.fromDist(ML_DIST, 6, 52));

    private static final int HASH_LOG = 14, HASH_SIZE = 1 << HASH_LOG, MIN_MATCH = 4, MAX_OFFSET = 1 << 18;
    private static final int MIN_MATCH_LEN = 8;
    private static final int MAX_CHAIN = 64;

    public Compressor(int level) { this.level = level; prevOff = new long[]{1, 4, 8}; }
    public void reset(int level) { this.level = level; prevOff = new long[]{1, 4, 8}; huffCodeLen = null; }

    public byte[] compress(byte[] src) {
        long maxOut = Constants.compressBound(src.length);
        if (maxOut <= 0) maxOut = src.length + 64;
        dst = new byte[(int) Math.min(maxOut, Integer.MAX_VALUE - 8)];
        dstPos = 0;
        Constants.writeLE32(dst, dstPos, Constants.ZSTD_MAGIC); dstPos += 4;

        // Frame header: always singleSegment=0, no content size, checksum enabled
        // Matches the reference CLI's conservative header format for best compatibility
        dst[dstPos++] = (byte)(1 << 2); // FHD = 0x04: checksum, no content size, multi-segment
        int wl = Math.max(17 + Math.min(8, level / 3), Constants.WINDOW_LOG_MIN);
        dst[dstPos++] = (byte)(((wl - Constants.WINDOW_LOG_MIN) & 0x1F) << 3);

        writeBlocks(src);
        // Content checksum (XXH64 truncated to 4 bytes)
        long xxh = XXH64.hash(src, 0, src.length, 0);
        Constants.writeLE32(dst, dstPos, (int)xxh); dstPos += 4;
        return Arrays.copyOf(dst, dstPos);
    }

    private void ensure(int n) {
        if (dstPos + n > dst.length)
            dst = Arrays.copyOf(dst, Math.max(dst.length * 2, dstPos + n));
    }

    private void writeBlocks(byte[] src) {
        int srcPos = 0, remaining = src.length;
        boolean first = true;
        while (remaining > 0 || first) {
            first = false;
            int chunk = Math.min(remaining, Constants.BLOCK_SIZE_MAX);
            boolean last = (remaining == chunk);
            int hdrPos = dstPos; dstPos += 3;

            if (chunk == 0) {
                Constants.writeLE24(dst, hdrPos, 1 | (Constants.BLOCK_RAW << 1));
                break;
            }

            int dataStart = dstPos;
            int compSize = tryLz77(src, srcPos, chunk);
            if (compSize > 0 && compSize < chunk * 8 / 10) {
                Constants.writeLE24(dst, hdrPos, (last ? 1 : 0) | (Constants.BLOCK_COMPRESSED << 1) | (compSize << 3));
                dstPos = dataStart + compSize;
            } else {
                dstPos = dataStart;
                boolean allSame = chunk > 1;
                int checkEnd = Math.min(chunk, 256);
                for (int i = 1; i < checkEnd; i++)
                    if (src[srcPos + i] != src[srcPos]) { allSame = false; break; }
                if (allSame && chunk > 256) {
                    for (int i = 256; i < chunk; i++)
                        if (src[srcPos + i] != src[srcPos]) { allSame = false; break; }
                }
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
            srcPos += chunk;
            remaining -= chunk;
        }
    }

    private int tryLz77(byte[] src, int base, int size) {
        if (size < 32) return 0;
        // Quick entropy check: if too many unique bytes in first 64 bytes, skip
        int check = Math.min(size, 64);
        int unique = 0;
        if (check <= 64) {
            for (int i = 0; i < check; i++) {
                int b = src[base + i] & 0xFF;
                if (!entropySeen[b]) { entropySeen[b] = true; unique++; }
            }
            for (int i = 0; i < check; i++) entropySeen[src[base + i] & 0xFF] = false;
        }
        if (unique > Math.max(check / 2, 32)) return 0;

        if (hashTable.length < HASH_SIZE) hashTable = new int[HASH_SIZE];
        Arrays.fill(hashTable, 0, hashTable.length, -1);

        int need = size / 4 + 10;
        if (litLens == null || litLens.length < need) {
            litLens = new int[need]; offs = new int[need]; matchLens = new int[need];
            litCodes = new byte[need]; ofCodes = new byte[need]; mlCodes = new byte[need];
            ofExtra = new byte[need];
        }

        int seqCount = 0, totalLits = 0;
        prevOff[0] = 1; prevOff[1] = 4; prevOff[2] = 8;

        // Build hash chain
        if (chainNext.length < size) chainNext = new int[size + 1];
        int[] cost = new int[size + 1];
        int[] prevPos = new int[size + 1];  // previous position in optimal path
        int[] matchLenAt = new int[size + 1];  // match length (-1 = literal)
        int[] matchOffAt = new int[size + 1];
        // Initialize
        cost[0] = 0;
        for (int i = 1; i <= size; i++) cost[i] = Integer.MAX_VALUE;

        for (int p = 0; p < size; p++) {
            if (cost[p] == Integer.MAX_VALUE) continue;

            // Option 1: literal byte
            int litCost = cost[p] + 8;
            if (litCost < cost[p + 1]) {
                cost[p + 1] = litCost;
                prevPos[p + 1] = p;
                matchLenAt[p + 1] = -1;
            }

            // Option 2: match at current position (if available)
            // Must get hash / save old head BEFORE updating the chain
            int matchH = -1;
            int matchOff = 0;
            if (p <= size - MIN_MATCH_LEN) {
                int h = hash4(src, base + p) & (HASH_SIZE - 1);
                matchH = hashTable[h];  // save old head before updating
                // Update hash chain
                if (p <= size - MIN_MATCH_LEN - 3) {
                    chainNext[p] = matchH;
                    hashTable[h] = p;
                }
                if (matchH >= 0 && p - matchH <= MAX_OFFSET) {
                    int len = matchLen(src, base + matchH, base + p, Math.min(size - p, 131072));
                    if (len >= MIN_MATCH_LEN) {
                        int end = p + len;
                        if (end > size) end = size;
                        int mlCode = matchLenCode(len);
                        int ofCode = offsetCode(p - matchH);
                        int mLBits = Constants.MATCHLEN_BITS[mlCode];
                        int oBits = Constants.OFFSET_BITS[ofCode];
                        int estCost = cost[p] + 16 + mLBits + oBits;
                        if (estCost < cost[end]) {
                            cost[end] = estCost;
                            prevPos[end] = p;
                            matchLenAt[end] = len;
                            matchOffAt[end] = p - matchH;
                        }
                    }
                }
            } else if (p <= size - 4) {
                int h = hash4(src, base + p) & (HASH_SIZE - 1);
                chainNext[p] = hashTable[h];
                hashTable[h] = p;
            }
        }

        // Traceback
        int p = size;
        java.util.ArrayList<Integer> seqStarts = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> seqLens = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> seqOffs = new java.util.ArrayList<>();
        while (p > 0) {
            int prev = prevPos[p];
            int len = matchLenAt[p];
            if (len > 0) {
                seqStarts.add(prev);
                seqLens.add(len);
                seqOffs.add(matchOffAt[p]);
            }
            p = prev;
        }

        // Convert to forward order
        seqCount = 0;
        totalLits = 0;
        int lastPos = 0;
        for (int i = seqStarts.size() - 1; i >= 0; i--) {
            int start = seqStarts.get(i);
            int len = seqLens.get(i);
            int off = seqOffs.get(i);
            int litLen = start - lastPos;
            if (litLen > 0) {
                totalLits += litLen;
            }
            if (len > 0) {
                litLens[seqCount] = litLen; offs[seqCount] = off; matchLens[seqCount] = len;
                litCodes[seqCount] = litLenCode(litLen); mlCodes[seqCount] = (byte)matchLenCode(len);
                int ofc = offsetCode(off);
                int ofe = off + 3 - Constants.OFFSET_BASE[ofc];
                if (off == (int)prevOff[0] && (litLen > 0 || seqCount == 0)) {
                    ofc = 0; ofe = 0;
                } else if (off == (int)prevOff[1]) {
                    ofc = 1; ofe = 0;
                } else if (off == (int)prevOff[2] && litLen > 0) {
                    ofc = 1; ofe = 1;
                }
                if (ofc == 0) {
                    if (litLen == 0 && seqCount > 0) {
                        long tmp = prevOff[0]; prevOff[0] = prevOff[1]; prevOff[1] = tmp;
                    }
                } else if (ofc == 1 && ofe == 0) {
                    long tmp = prevOff[1]; prevOff[1] = prevOff[0]; prevOff[0] = tmp;
                } else if (ofc == 1 && ofe == 1) {
                    prevOff[2] = prevOff[1]; prevOff[1] = prevOff[0]; prevOff[0] = off;
                } else {
                    prevOff[2] = prevOff[1]; prevOff[1] = prevOff[0]; prevOff[0] = off;
                }
                ofCodes[seqCount] = (byte)ofc;
                if (ofExtra == null || ofExtra.length < seqCount + 1) ofExtra = new byte[seqCount + 16];
                ofExtra[seqCount] = (byte)ofe;
                seqCount++;
                lastPos = start + len;
            }
        }
        int trailing = size - lastPos;
        if (seqCount == 0) return 0;
        totalLits += trailing;

        int outStart = dstPos;
        ensure(2048 + totalLits + (totalLits / 4));

        // Write literals section – try Huffman if it saves space
        int huffCompSize = 0;
        if (totalLits > 8) {
            int scratchPos = dstPos + 8;
            ensure(scratchPos + totalLits + 16);
            // Check if we can reuse previous Huffman tree (treeless)
            int[] curCodeLen = Huff.computeCodeLengths2(src, base, totalLits, 11);
            boolean treeless = huffCodeLen != null && curCodeLen != null && arraysEqual(huffCodeLen, curCodeLen, 256);
            if (!treeless && curCodeLen != null) huffCodeLen = curCodeLen;
            int cSize;
            if (treeless) {
                cSize = Huff.compressTreeless(src, base, totalLits, dst, scratchPos);
            } else {
                cSize = Huff.compress(src, base, totalLits, dst, scratchPos);
            }
            if (cSize > 0 && cSize < totalLits) {
                int regen = totalLits;
                int litType = treeless ? 3 : 2;
                boolean use4 = totalLits > 1024;
                int szEnc = use4 ? 1 : 0; // 01 = 4 streams 3-byte header, 00 = single stream
                dst[dstPos] = (byte)((szEnc << 6) | ((cSize >> 8) & 3) << 4 | ((regen >> 8) & 3) << 2 | litType);
                dst[dstPos + 1] = (byte)cSize;
                dst[dstPos + 2] = (byte)regen;
                System.arraycopy(dst, scratchPos, dst, dstPos + 3, cSize);
                dstPos += 3 + cSize;
                huffCompSize = 1;
            }
        }

        if (huffCompSize == 0) {
            // Write raw literals
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
        }

        dst[dstPos++] = seqCount < 0x7F ? (byte)seqCount : (byte)((seqCount >> 8) | 0x80);
        if (seqCount >= 0x7F) {
            if (seqCount < 0x7F00) dst[dstPos++] = (byte)seqCount;
            else { dst[dstPos - 1] = (byte)0xFF; dst[dstPos++] = (byte)(seqCount - 0x7F00); dst[dstPos++] = (byte)((seqCount - 0x7F00) >> 8); }
        }
        // Write sequence FSE table modes
        int[] llFreq = new int[36], ofFreq = new int[29], mlFreq = new int[53];
        for (int i = 0; i < seqCount; i++) {
            int llc = litCodes[i] & 0xFF, ofc = ofCodes[i] & 0xFF, mlc = mlCodes[i] & 0xFF;
            if (llc < 36) llFreq[llc]++; if (ofc < 29) ofFreq[ofc]++; if (mlc < 53) mlFreq[mlc]++;
        }
        int llMode = 0, ofMode = 0, mlMode = 0, modePos = dstPos++;
        if (maybeCompressedFse(llFreq, 6, 35))  { writeFseDist(llFreq, 6, 35); llMode = 2; }
        if (maybeCompressedFse(ofFreq, 5, 28))  { writeFseDist(ofFreq, 5, 28); ofMode = 2; }
        if (maybeCompressedFse(mlFreq, 6, 52))  { writeFseDist(mlFreq, 6, 52); mlMode = 2; }
        dst[modePos] = (byte)((llMode << 6) | (ofMode << 4) | (mlMode << 2));

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
        stream.writeBits(ofExtra[ls] & 0xFF, Constants.OFFSET_BITS[ofCode]);
        stream.flush();

        // Encode previous sequences in reverse order
        for (int s = seqCount - 2; s >= 0; s--) {
            int llc = litCodes[s] & 0xFF, ofc = ofCodes[s] & 0xFF, mlc = mlCodes[s] & 0xFF;
            ofState = OF_TABLE.encode(stream, ofState, ofc);
            mlState = ML_TABLE.encode(stream, mlState, mlc);
            llState = LL_TABLE.encode(stream, llState, llc);
            stream.writeBits(litLens[s] - Constants.LITLEN_BASE[llc], Constants.LITLEN_BITS[llc]);
            stream.writeBits(matchLens[s] - Constants.MATCHLEN_BASE[mlc], Constants.MATCHLEN_BITS[mlc]);
            stream.writeBits(ofExtra[s] & 0xFF, Constants.OFFSET_BITS[ofc]);
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

    private static int matchLen(byte[] src, int a, int b, int max) {
        int len = 0;
        while (len + 8 <= max) {
            long va = readLE64(src, a + len);
            long vb = readLE64(src, b + len);
            if (va != vb) {
                int xor = (int)(va ^ vb);
                xor = (xor & 0xFF) != 0 ? 0
                    : (xor & 0xFF00) != 0 ? 1
                    : (xor & 0xFF0000) != 0 ? 2
                    : (xor & 0xFF000000) != 0 ? 3
                    : (xor & 0xFF00000000L) != 0 ? 4
                    : (xor & 0xFF0000000000L) != 0 ? 5
                    : (xor & 0xFF000000000000L) != 0 ? 6
                    : 7;
                return len + xor;
            }
            len += 8;
        }
        while (len < max && src[a + len] == src[b + len]) len++;
        return len;
    }

    private static long readLE64(byte[] d, int o) {
        return (d[o]&0xFFL)|((d[o+1]&0xFFL)<<8)|((d[o+2]&0xFFL)<<16)|((d[o+3]&0xFFL)<<24)
             | ((d[o+4]&0xFFL)<<32)|((d[o+5]&0xFFL)<<40)|((d[o+6]&0xFFL)<<48)|((d[o+7]&0xFFL)<<56);
    }

    private static int hash4(byte[] d, int p) {
        int h = (d[p]&0xFF)|((d[p+1]&0xFF)<<8)|((d[p+2]&0xFF)<<16)|((d[p+3]&0xFF)<<24);
        return (h * 0x9E3779B9) >>> (32 - HASH_LOG);
    }
    private static int offsetCode(int off) {
        if (off <= 0) return 0;
        int stored = off + 3;
        return Math.min(31 - Integer.numberOfLeadingZeros(stored), 30);
    }
    private static int matchLenCode(int len) { for (int i = Constants.MATCHLEN_BASE.length - 1; i >= 0; i--) if (Constants.MATCHLEN_BASE[i] <= len) return i; return 0; }
    private static byte litLenCode(int len) { for (int i = Constants.LITLEN_BASE.length - 1; i >= 0; i--) if (Constants.LITLEN_BASE[i] <= len) return (byte)i; return 0; }
    private static boolean arraysEqual(int[] a, int[] b, int n) {
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return false; return true;
    }

    private boolean maybeCompressedFse(int[] freqs, int accLog, int maxSym) {
        int totalNz = 0;
        for (int f : freqs) if (f > 0) totalNz++;
        if (totalNz <= 3) return false;
        int[] dist = maxSym > 40 ? ML_DIST : (maxSym > 30 ? LL_DIST : OF_DIST);
        int diff = 0, check = Math.min(maxSym + 1, Math.min(dist.length, freqs.length));
        for (int i = 0; i < check; i++) {
            int df = dist[i] == -1 ? 1 : dist[i];
            if (Math.abs(df - freqs[i]) > 2) diff++;
        }
        return diff > 6;
    }

    private void writeFseDist(int[] freqs, int accLog, int maxSym) {
        int total = 0;
        for (int f : freqs) total += f;
        if (total <= 0) { writeFseRawZero(freqs, accLog, maxSym); return; }
        int size = 1 << accLog;
        int[] norm = new int[maxSym + 1];
        int ns = 0, maxF = 0, maxI = 0;
        for (int i = 0; i <= maxSym; i++) {
            if (freqs[i] > 0) {
                int n = Math.max(1, freqs[i] * size / total);
                norm[i] = n; ns += n;
                if (freqs[i] > maxF) { maxF = freqs[i]; maxI = i; }
            }
        }
        int adj = size - ns;
        if (adj != 0 && maxI <= maxSym) { norm[maxI] += adj; ns += adj; }
        if (maxI <= maxSym && norm[maxI] < 1) norm[maxI] = 1;

        int start = dstPos;
        // Write bitstream header
        long container = 0;
        int nBits = 0, remaining = size + 1, threshold = size, nb = accLog + 1;
        container |= (accLog - 5) & 0xFL; nBits += 4;
        int sym = 0;
        boolean prevZero = false;
        while (remaining > 1 && sym <= maxSym) {
            int cnt = norm[sym];
            if (cnt <= 0) {
                // Zero-count symbol - use 2-bit repeat encoding
                int repeatStart = sym;
                while (sym <= maxSym && (sym >= norm.length || norm[sym] <= 0)) sym++;
                int zeros = sym - repeatStart;
                while (zeros > 0) {
                    int chunk = Math.min(zeros, 6); // max 6 zeros per 2-bit sequence
                    while (chunk >= 3) {
                        container |= 3L << nBits; nBits += 2; chunk -= 3;
                    }
                    if (chunk > 0) { container |= (long)(chunk & 3) << nBits; nBits += 2; }
                    zeros -= chunk;
                    zeros = Math.min(zeros, 6);
                }
                // Flush after zero-repeat
                while (nBits >= 8) { dst[dstPos++] = (byte)container; container >>>= 8; nBits -= 8; }
                if (sym > maxSym) break;
                cnt = norm[sym];
            }
            // Encode count
            int max = (2 * threshold - 1) - remaining;
            if (max >= 0) {
                if (cnt - 1 < max) {
                    container |= (long)(cnt - 1) << nBits; nBits += nb - 1;
                } else {
                    int val = ((cnt - 1) + max) >>> 1;
                    int low = ((cnt - 1) + max) & 1;
                    container |= (long)val << nBits; nBits += nb - 1;
                    container |= (long)low << nBits; nBits += 1;
                }
            } else {
                container |= (long)(cnt - 1) << nBits; nBits += nb;
            }
            while (nBits >= 8) { dst[dstPos++] = (byte)container; container >>>= 8; nBits -= 8; }
            if (cnt > 0) remaining -= cnt;
            if (remaining < threshold && remaining > 1) {
                nb = 32 - Integer.numberOfLeadingZeros(remaining) + 1;
                threshold = 1 << (nb - 1);
            }
            sym++;
        }
        // Flush remaining bits
        while (nBits > 0) { dst[dstPos++] = (byte)container; container >>>= 8; nBits -= 8; }
        // Write accuracyLog into first nibble
        dst[start] = (byte)((dst[start] & 0xF0) | (accLog - 5));
    }

    private void writeFseRawZero(int[] freqs, int accLog, int maxSym) {
        int start = dstPos;
        dst[dstPos++] = (byte)(accLog - 5);
        // Write all counts as 1s (worst case)
        long container = accLog - 5;
        int bits = 4, size = 1 << accLog, remaining = size + 1, threshold = size, nb = accLog + 1;
        for (int s = 0; s <= maxSym && remaining > 1; s++) {
            int cnt = 1;
            int max = (2 * threshold - 1) - remaining;
            if (max >= 0) {
                if (0 < max) {
                    container |= 0L << bits; bits += nb - 1;
                } else {
                    container |= 0L << bits; bits += nb;
                }
            } else {
                container |= 0L << bits; bits += nb;
            }
            while (bits >= 8) { dst[dstPos++] = (byte)container; container >>>= 8; bits -= 8; }
            remaining -= cnt;
            if (remaining < threshold) {
                nb = 32 - Integer.numberOfLeadingZeros(remaining) + 1;
                threshold = 1 << (nb - 1);
            }
        }
        while (bits > 0) { dst[dstPos++] = (byte)container; container >>>= 8; bits -= 8; }
        dst[start] = (byte)((dst[start] & 0xF0) | (accLog - 5));
    }
}
