package org.zstjd.internal;
import org.zstjd.ZstdException;

/** Container-based backward bitstream reader matching reference's BIT_DStream_t. */
public final class BitReader {
    private long container;
    private int bitsConsumed;
    private int streamSize;

    public void init(byte[] src, int off, int len) {
        streamSize = len;
        if (len < 1) throw new ZstdException(ZstdException.CORRUPTION_DETECTED, "Empty bitstream");

        if (len >= 8) {
            // Load last 8 bytes as little-endian (MEM_readLEST)
            container = (long)(src[off + len - 8] & 0xFF)
                     | ((long)(src[off + len - 7] & 0xFF) << 8)
                     | ((long)(src[off + len - 6] & 0xFF) << 16)
                     | ((long)(src[off + len - 5] & 0xFF) << 24)
                     | ((long)(src[off + len - 4] & 0xFF) << 32)
                     | ((long)(src[off + len - 3] & 0xFF) << 40)
                     | ((long)(src[off + len - 2] & 0xFF) << 48)
                     | ((long)(src[off + len - 1] & 0xFF) << 56);
        } else {
            container = 0;
            for (int i = 0; i < len; i++) {
                container |= (long)(src[off + i] & 0xFF) << (i * 8);
            }
        }

        int lastByte = src[off + len - 1] & 0xFF;
        if (lastByte == 0) throw new ZstdException(ZstdException.CORRUPTION_DETECTED, "No end mark");
        int hb = 31 - Integer.numberOfLeadingZeros(lastByte);
        bitsConsumed = 8 - hb;

        if (len < 8) {
            bitsConsumed += (8 - len) * 8; // account for zero bytes above loaded data
        }
    }

    public int readBits(int n) {
        int start = (int)((64 - bitsConsumed - n) & 63);
        int result = (int)((container >>> start) & ((1L << n) - 1));
        bitsConsumed += n;
        return result;
    }
}
