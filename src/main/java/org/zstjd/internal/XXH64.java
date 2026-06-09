package org.zstjd.internal;

public final class XXH64 {
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    public static long hash(byte[] data, int off, int len, long seed) {
        long h64;
        int p = off, end = off + len;

        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            int limit = end - 32;
            while (p <= limit) {
                v1 = round(v1, readLE64(data, p)); p += 8;
                v2 = round(v2, readLE64(data, p)); p += 8;
                v3 = round(v3, readLE64(data, p)); p += 8;
                v4 = round(v4, readLE64(data, p)); p += 8;
            }
            h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + P5;
        }

        h64 += len;
        while (p + 8 <= end) {
            long k1 = round(0, readLE64(data, p));
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h64 ^= (readLE32(data, p) & 0xFFFFFFFFL) * P1;
            h64 = Long.rotateLeft(h64, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h64 ^= (data[p] & 0xFFL) * P5;
            h64 = Long.rotateLeft(h64, 11) * P1;
            p++;
        }
        return finalize(h64);
    }

    private static long round(long acc, long v) {
        acc += v * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long v) {
        acc ^= round(0, v);
        acc = acc * P1 + P4;
        return acc;
    }

    private static long finalize(long h64) {
        h64 ^= h64 >>> 33;
        h64 *= P2;
        h64 ^= h64 >>> 29;
        h64 *= P3;
        h64 ^= h64 >>> 32;
        return h64;
    }

    private static long readLE64(byte[] d, int o) {
        return (d[o]&0xFFL)|((d[o+1]&0xFFL)<<8)|((d[o+2]&0xFFL)<<16)|((d[o+3]&0xFFL)<<24)
             | ((d[o+4]&0xFFL)<<32)|((d[o+5]&0xFFL)<<40)|((d[o+6]&0xFFL)<<48)|((d[o+7]&0xFFL)<<56);
    }

    private static int readLE32(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }
}
