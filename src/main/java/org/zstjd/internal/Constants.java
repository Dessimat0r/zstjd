package org.zstjd.internal;

public final class Constants {
    public static final int ZSTD_MAGIC = 0xFD2FB528;
    public static final int WINDOW_LOG_MIN = 10;
    public static final int WINDOW_LOG_MAX = 31;
    public static final int BLOCK_SIZE_MAX = 128 * 1024;
    public static final int MAX_DECOMPRESS_SIZE = 512 * 1024 * 1024;

    public static final int LITERALS_RAW = 0;
    public static final int LITERALS_RLE = 1;
    public static final int LITERALS_COMPRESSED = 2;
    public static final int LITERALS_TREELESS = 3;

    public static final int BLOCK_RAW = 0;
    public static final int BLOCK_RLE = 1;
    public static final int BLOCK_COMPRESSED = 2;

    public static final int CONTENTSIZE_UNKNOWN = -1;
    public static final int CONTENTSIZE_ERROR = -2;

    public static final int DEFAULT_LEVEL = 3;
    public static final int MIN_LEVEL = -131072;
    public static final int MAX_LEVEL = 22;

    public static final int[] LITLEN_BASE;
    public static final int[] LITLEN_BITS;
    public static final int[] MATCHLEN_BASE;
    public static final int[] MATCHLEN_BITS;
    public static final int[] OFFSET_BASE;
    public static final int[] OFFSET_BITS;

    static {
        LITLEN_BASE = new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,20,22,24,28,32,40,48,64,128,256,512,1024,2048,4096,8192,16384,32768,65536};
        LITLEN_BITS = new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,2,2,3,3,4,6,7,8,9,10,11,12,13,14,15,16};
        MATCHLEN_BASE = new int[]{3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,37,39,41,43,47,51,59,67,83,99,131,259,515,1027,2051,4099,8195,16387,32771,65539};
        MATCHLEN_BITS = new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,2,2,3,3,4,4,5,7,8,9,10,11,12,13,14,15,16};
        OFFSET_BASE = new int[]{1,2,4,8,16,32,64,128,256,512,1024,2048,4096,8192,16384,32768,65536,131072,262144,524288,1048576,2097152,4194304,8388608,16777216,33554432,67108864,134217728,268435456,536870912,1073741824};
        OFFSET_BITS = new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31};
    }

    public static long compressBound(long srcSize) {
        if (Long.compareUnsigned(srcSize, 0xFF00FF00FF00FF00L) >= 0) return 0;
        long b = srcSize + (srcSize >> 8);
        if (srcSize < (128L << 10))
            b += ((128L << 10) - srcSize) >> 11;
        return b;
    }

    static int readLE32(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }
    static int readLE24(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16);
    }
    static int readLE16(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8);
    }
    static long readLE64(byte[] d, int o) {
        return (d[o]&0xFFL)|((d[o+1]&0xFFL)<<8)|((d[o+2]&0xFFL)<<16)|((d[o+3]&0xFFL)<<24)
             | ((d[o+4]&0xFFL)<<32)|((d[o+5]&0xFFL)<<40)|((d[o+6]&0xFFL)<<48)|((d[o+7]&0xFFL)<<56);
    }
    static void writeLE32(byte[] d, int o, int v) {
        d[o]=(byte)v; d[o+1]=(byte)(v>>8); d[o+2]=(byte)(v>>16); d[o+3]=(byte)(v>>24);
    }
    static void writeLE24(byte[] d, int o, int v) {
        d[o]=(byte)v; d[o+1]=(byte)(v>>8); d[o+2]=(byte)(v>>16);
    }
}
