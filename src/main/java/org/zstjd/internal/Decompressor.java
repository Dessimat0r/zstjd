package org.zstjd.internal;
import org.zstjd.ZstdException;

import java.util.Arrays;

public final class Decompressor {
    private byte[] dst;
    private int dstPos;
    private FseTable llTable, ofTable, mlTable;
    private long[] prevOff = {1, 4, 8};
    private byte[] literalsBuf = new byte[Constants.BLOCK_SIZE_MAX];
    private short[] huffDecodeTable = new short[1 << 12];
    private byte[] huffDecodeNbBits = new byte[1 << 12];
    private int huffTableLog;
    private Dict dict;

    public void useDict(Dict d) { this.dict = d; }

    private static final int[] LL_DIST = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    private static final int[] OF_DIST = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};
    private static final int[] ML_DIST = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1};

    public Decompressor() { dst = new byte[4096]; }
    public void reset() { dstPos = 0; prevOff = new long[]{1, 4, 8}; llTable = null; ofTable = null; mlTable = null; huffTableLog = 0; }

    public byte[] decompress(byte[] src) { return decompress(src, 0, src.length); }

    public int decompress(byte[] src, int off, int len, byte[] out, int outOff) {
        externalBuffer = true;
        byte[] savedDst = dst;
        int savedPos = dstPos;
        dstPos = 0;
        dst = out;
        try {
            decompressInternal(src, off, len);
            return dstPos;
        } finally {
            dstPos = savedPos;
            dst = savedDst;
            externalBuffer = false;
        }
    }

    public byte[] decompress(byte[] src, int off, int len) {
        externalBuffer = false;
        decompressInternal(src, off, len);
        return Arrays.copyOf(dst, dstPos);
    }

    private void decompressInternal(byte[] src, int off, int len) {
        dstPos = 0;
        int pos = off, end = off + len;
        while (pos + 4 <= end) {
            int magic = Constants.readLE32(src, pos);
            // Skippable frames: magic in range 0x184D2A50-0x184D2A5F
            if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
                if (pos + 8 > end) break;
                int skipSize = Constants.readLE32(src, pos + 4);
                pos += 8 + skipSize;
                continue;
            }
            if (magic != Constants.ZSTD_MAGIC) break;
            pos += 4;
            boolean hasChecksum = ((src[pos] >> 2) & 1) != 0;
            pos = parseFrameHeader(src, pos, end);
            int frameStart = dstPos;
            while (pos + 3 <= end) {
                int bh = Constants.readLE24(src, pos); pos += 3;
                boolean last = (bh & 1) != 0;
                int type = (bh >> 1) & 3, bSize = (bh >> 3) & 0x1FFFFF;
                if (type == Constants.BLOCK_RAW) { grow(dstPos + bSize); System.arraycopy(src, pos, dst, dstPos, bSize); dstPos += bSize; pos += bSize; }
                else if (type == Constants.BLOCK_RLE) { byte v = src[pos++]; grow(dstPos + bSize); Arrays.fill(dst, dstPos, dstPos + bSize, v); dstPos += bSize; }
                else if (type == Constants.BLOCK_COMPRESSED) { pos += decodeCompressed(src, pos, bSize); }
                else throw new ZstdException(ZstdException.CORRUPTION_DETECTED, "Bad block type");
                if (last) break;
            }
            if (hasChecksum && pos + 4 <= end) {
                int storedCk = Constants.readLE32(src, pos);
                pos += 4;
                long computedCk = XXH64.hash(dst, frameStart, dstPos - frameStart, 0);
                if ((int)computedCk != storedCk)
                    throw new ZstdException(ZstdException.CHECKSUM_WRONG, "Checksum mismatch: frame=" + (frameStart) + " stored=0x" + Integer.toHexString(storedCk) + " computed=0x" + Integer.toHexString((int)computedCk));
            }
        }
    }

    private int parseFrameHeader(byte[] src, int pos, int end) {
        int fhd = src[pos++] & 0xFF;
        int fcsId = (fhd >> 6) & 3;
        boolean single = ((fhd >> 5) & 1) != 0;
        if (!single) {
            int wl = src[pos++] & 0xFF;
            int wLog = (wl >> 3) + Constants.WINDOW_LOG_MIN + (wl & 7);
            int win = (int) Math.min(1L << Math.min(wLog, Constants.WINDOW_LOG_MAX), 1 << 25);
            if (dst.length < win) dst = new byte[win];
        }
        if (dst.length < 1 << 20) dst = new byte[1 << 20];
        if (fcsId == 0 && single) pos += 1;
        else         if (fcsId == 0 && single) pos += 1;
        else if (fcsId == 1) pos += 2;
        else if (fcsId == 2) pos += 4;
        else if (fcsId == 3) pos += 8;
        int dictFlag = fhd & 3;
        if (dictFlag > 0) {
            int dictIdLen = 1 << (dictFlag - 1); // 1, 2, or 4 bytes
            if (dictFlag == 3) dictIdLen = 4;
            // We don't validate the dict ID against our loaded dict here;
            // the caller is responsible for providing the correct dict.
            pos += dictIdLen;
        }
        return pos;
    }

    private int decodeCompressed(byte[] src, int pos, int size) {
        int start = pos;
        // Decode literals into reusable buffer
        if (literalsBuf.length < Constants.BLOCK_SIZE_MAX) literalsBuf = new byte[Constants.BLOCK_SIZE_MAX];
        byte[] literals = literalsBuf;
        int litLen = 0;
        int h = src[pos++] & 0xFF;
        int litBlockType = h & 3;
        int sizeEnc = (h >> 6) & 3;
        if (litBlockType == 0 || litBlockType == 1) {
            // Raw or RLE: size encoding is at bits 2-3
            int rawSizeEnc = (h >> 2) & 3;
            int regen;
            if (rawSizeEnc == 0 || rawSizeEnc == 2) {
                regen = h >>> 3;
            } else if (rawSizeEnc == 1) {
                regen = (h & 0xFF) | ((src[pos] & 0xFF) << 8);
                regen >>>= 4; pos++;
            } else {
                regen = (h & 0xFF) | ((src[pos] & 0xFF) << 8) | ((src[pos + 1] & 0xFF) << 16);
                regen >>>= 4; pos += 2;
            }
            if (litBlockType == 0) { // RAW
                litLen = regen;
                System.arraycopy(src, pos, literals, 0, regen);
                pos += regen;
            } else { // RLE
                litLen = regen;
                byte v = src[pos++];
                Arrays.fill(literals, 0, litLen, v);
            }
        } else if (litBlockType == 2 || litBlockType == 3) { // Compressed or Treeless literals (Huffman)
            int regenSize, compSize;
            if (sizeEnc == 0 || sizeEnc == 1) { // 3-byte header
                regenSize = ((h >> 2) & 3) << 8 | (src[pos + 1] & 0xFF);
                compSize = ((h >> 4) & 3) << 8 | (src[pos] & 0xFF);
                pos += 2;
            } else if (sizeEnc == 2) { // 4-byte header
                regenSize = ((h >> 2) & 3) << 14 | (src[pos] & 0xFF) << 6 | ((src[pos + 1] >> 6) & 0x3F);
                compSize = ((h >> 4) & 3) << 12 | (src[pos + 1] & 0x3F) << 6 | (src[pos + 2] & 0xFF);
                pos += 3;
            } else { // 5-byte header
                regenSize = ((h >> 2) & 3) << 18 | (src[pos] & 0xFF) << 10 | ((src[pos + 1] & 0xFF) << 2) | ((src[pos + 2] >> 6) & 3);
                compSize = ((h >> 4) & 3) << 16 | (src[pos + 2] & 0x3F) << 10 | ((src[pos + 3] & 0xFF) << 2) | ((src[pos + 4] >> 6) & 3);
                pos += 5;
            }
            if (regenSize > literalsBuf.length) literalsBuf = new byte[regenSize];
            literals = literalsBuf;
            int decoded;
            if (litBlockType == 2) {
                int[] huffCodeLen = new int[256];
                int hdrSize = Huff.readHuffHeader(src, pos, compSize, huffCodeLen);
                if (hdrSize <= 0) return size;
                int bitOff = pos + hdrSize;
                int bitSize = compSize - hdrSize;
                huffTableLog = Huff.buildTable(huffCodeLen, huffDecodeTable, huffDecodeNbBits);
                if ((1 << huffTableLog) > huffDecodeTable.length) { huffDecodeTable = new short[1 << huffTableLog]; huffDecodeNbBits = new byte[1 << huffTableLog]; huffTableLog = Huff.buildTable(huffCodeLen, huffDecodeTable, huffDecodeNbBits); }
                if (sizeEnc == 0) {
                    decoded = Huff.decodeStream(src, bitOff, bitSize, literals, 0, regenSize, huffDecodeTable, huffDecodeNbBits, huffTableLog);
                } else {
                    decoded = Huff.decodeStream4(src, bitOff, bitSize, literals, 0, regenSize, huffDecodeTable, huffDecodeNbBits, huffTableLog);
                }
            } else {
                // Treeless (type 3) or repeat — use previous or dict table
                if (huffTableLog <= 0) {
                    if (dict != null) {
                        huffDecodeTable = dict.huffTable;
                        huffDecodeNbBits = dict.huffNbBits;
                        huffTableLog = dict.huffTableLog;
                    } else {
                        return size;
                    }
                }
                int bitOff = pos;
                int bitSize = compSize;
                if (sizeEnc == 0) {
                    decoded = Huff.decodeStream(src, bitOff, bitSize, literals, 0, regenSize, huffDecodeTable, huffDecodeNbBits, huffTableLog);
                } else {
                    decoded = Huff.decodeStream4(src, bitOff, bitSize, literals, 0, regenSize, huffDecodeTable, huffDecodeNbBits, huffTableLog);
                }
            }
            if (decoded != regenSize) return size;
            litLen = regenSize;
            pos += compSize;
        } else {
            return size;
        }

        if (pos - start >= size) return size;
        int remaining = size - (pos - start);
        if (remaining > 0) pos += decodeSequences(src, pos, remaining, literals, litLen);
        return pos - start;
    }

    private int decodeSequences(byte[] src, int pos, int max, byte[] literals, int litTotal) {
        int consumed = 0;
        int h = src[pos] & 0xFF; consumed++;
        int seqCount;
        if (h < 128) seqCount = h;
        else if (h < 255) { seqCount = ((h-128) << 8) | (src[pos+1] & 0xFF); consumed++; }
        else { seqCount = Constants.readLE16(src, pos+1) + 0x7F00; consumed += 2; }
        if (seqCount == 0) return consumed;
        int modes = src[pos + consumed] & 0xFF; consumed++;
        int llM = (modes >> 6) & 3, ofM = (modes >> 4) & 3, mlM = (modes >> 2) & 3;

        int frStart = pos + consumed;
        ForwardReader fr = new ForwardReader(src, frStart);
        llTable = readSeqTable(fr, llM, llTable, LL_DIST, 6, 35, 0);
        ofTable = readSeqTable(fr, ofM, ofTable, OF_DIST, 5, 28, 1);
        mlTable = readSeqTable(fr, mlM, mlTable, ML_DIST, 6, 52, 2);
        int frEnd = fr.bytePos(); fr.align();
        consumed += frEnd - frStart;

        int streamSize = max - consumed;
        if (streamSize <= 0) return max;

        // Initialize container-based reader (matches reference BIT_DStream_t)
        BitReader reader = new BitReader();
        reader.init(src, pos + consumed, streamSize);

        int llSt = reader.readBits(llTable.accuracyLog);
        int ofSt = reader.readBits(ofTable.accuracyLog);
        int mlSt = reader.readBits(mlTable.accuracyLog);

        int litIdx = 0;
        for (int i = 0; i < seqCount; i++) {
            int llCode = llTable.symbol[llSt] & 0xFFFF;
            int ofCode = ofTable.symbol[ofSt] & 0xFFFF;
            int mlCode = mlTable.symbol[mlSt] & 0xFFFF;

            int ofBits = Constants.OFFSET_BITS[Math.min(ofCode, 31)];
            int mlBits = Constants.MATCHLEN_BITS[Math.min(mlCode, 52)];
            int llBits = Constants.LITLEN_BITS[Math.min(llCode, 35)];

            long ofVal = reader.readBits(ofBits);
            long mlVal = reader.readBits(mlBits);
            long llVal = reader.readBits(llBits);

            int litLen = Constants.LITLEN_BASE[llCode] + (int)llVal;
            int matchLen = Constants.MATCHLEN_BASE[mlCode] + (int)mlVal;
            int offset = Constants.OFFSET_BASE[ofCode] + (int)ofVal;
            offset = resolveOffset(offset, litLen, i);

            if (litLen > 0 && litIdx + litLen <= litTotal) {
                grow(dstPos + litLen);
                System.arraycopy(literals, litIdx, dst, dstPos, litLen);
                dstPos += litLen;
                litIdx += litLen;
            }

            if (matchLen > 0 && offset > 0) {
                if (offset <= dstPos) {
                    // Normal match within decoded output
                    int sOff = dstPos - offset;
                    grow(dstPos + matchLen);
                    if (offset >= matchLen) System.arraycopy(dst, sOff, dst, dstPos, matchLen);
                    else for (int j = 0; j < matchLen; j++) dst[dstPos + j] = dst[sOff + j];
                    dstPos += matchLen;
                } else if (dict != null && offset <= dstPos + dict.content.length) {
                    // Match references dictionary content
                    int dictOff = dict.content.length - (offset - dstPos);
                    int fromDict = Math.min(matchLen, dict.content.length - dictOff);
                    if (fromDict > 0) {
                        grow(dstPos + matchLen);
                        System.arraycopy(dict.content, dictOff, dst, dstPos, fromDict);
                        dstPos += fromDict;
                    }
                    // If match extends beyond dict content, fill from newly decoded bytes
                    int remaining = matchLen - fromDict;
                    if (remaining > 0 && offset <= dstPos) {
                        int sOff = dstPos - offset;
                        for (int j = 0; j < remaining; j++)
                            dst[dstPos + j] = dst[sOff + j];
                        dstPos += remaining;
                    }
                }
            }

            if (i < seqCount - 1) {
                int llNb = llTable.numBits[llSt] & 0xFF;
                long llNext = reader.readBits(llNb);
                llSt = (llTable.newState[llSt] & 0xFFFF) + (int)llNext;

                int mlNb = mlTable.numBits[mlSt] & 0xFF;
                long mlNext = reader.readBits(mlNb);
                mlSt = (mlTable.newState[mlSt] & 0xFFFF) + (int)mlNext;

                int ofNb = ofTable.numBits[ofSt] & 0xFF;
                long ofNext = reader.readBits(ofNb);
                ofSt = (ofTable.newState[ofSt] & 0xFFFF) + (int)ofNext;
            }
        }
        if (litIdx < litTotal) {
            int last = litTotal - litIdx;
            grow(dstPos + last);
            System.arraycopy(literals, litIdx, dst, dstPos, last);
            dstPos += last;
        }
        return consumed + streamSize;
    }

    private FseTable readSeqTable(ForwardReader fr, int mode, FseTable prev, int[] dist, int acc, int max, int tableType) {
        if (mode == 0) return FseTable.fromDist(dist, acc, max);
        if (mode == 1) { int s = fr.read(8); return FseTable.fromDist(new int[]{-1}, 1, 0); }
        if (mode == 2) return FseTable.readFrom(fr, max);
        if (mode == 3) {
            if (prev != null) return prev;
            if (dict != null) {
                if (tableType == 0) return dict.llTable;
                if (tableType == 1) return dict.ofTable;
                return dict.mlTable;
            }
        }
        return FseTable.fromDist(dist, acc, max);
    }

    private int resolveOffset(int offset, int litLen, int seqIdx) {
        if (offset <= 3) {
            int idx = offset - 1;
            if (seqIdx > 0 && litLen == 0) idx++;
            if (idx == 0) return (int)prevOff[0];
            long v = idx < 3 ? prevOff[idx] : prevOff[0] - 1;
            if (idx > 1) prevOff[2] = prevOff[1];
            prevOff[1] = prevOff[0]; prevOff[0] = v;
            return (int)v;
        }
        long v = offset - 3;
        prevOff[2] = prevOff[1]; prevOff[1] = prevOff[0]; prevOff[0] = v;
        return (int)v;
    }

    public static int getContentSize(byte[] data) {
        if (data.length < 8) return Constants.CONTENTSIZE_ERROR;
        if (Constants.readLE32(data, 0) != Constants.ZSTD_MAGIC) return Constants.CONTENTSIZE_ERROR;
        int fhd = data[4] & 0xFF;
        int fcsId = (fhd >> 6) & 3;
        boolean single = ((fhd >> 5) & 1) != 0;
        int skip = 5;
        if (!single) skip++;
        if (fcsId == 0 && single) return data.length > skip ? data[skip] & 0xFF : Constants.CONTENTSIZE_UNKNOWN;
        if (fcsId == 1) return data.length >= skip + 2 ? (Constants.readLE16(data, skip) & 0xFFFF) : Constants.CONTENTSIZE_UNKNOWN;
        if (fcsId == 2) return data.length >= skip + 4 ? (int)(Constants.readLE32(data, skip) & 0xFFFFFFFFL) : Constants.CONTENTSIZE_UNKNOWN;
        if (fcsId == 3) return data.length >= skip + 8 ? (int) Constants.readLE64(data, skip) : Constants.CONTENTSIZE_UNKNOWN;
        return Constants.CONTENTSIZE_UNKNOWN;
    }

    private boolean externalBuffer;
    private void grow(int n) {
        if (n > dst.length) {
            if (externalBuffer)
                throw new ZstdException(ZstdException.DST_SIZE_TOO_SMALL,
                    "Output buffer too small: need " + n + " but have " + dst.length);
            int newSize = Math.max(n, Math.max(dst.length * 2, 1 << 20));
            if (newSize < 0 || newSize > Constants.MAX_DECOMPRESS_SIZE)
                throw new ZstdException(ZstdException.DST_SIZE_TOO_SMALL,
                    "Decompressed size too large: " + newSize);
            dst = Arrays.copyOf(dst, newSize);
        }
    }
}
