package org.zstjd.internal;

public final class BitStream {
    private final byte[] data;
    private int pos;
    private long container;
    private int bits;

    private final int start;

    public BitStream(byte[] data, int start) {
        this.data = data;
        this.pos = start;
        this.start = start;
    }

    public void writeBits(int value, int n) {
        container |= (long) (value & ((1L << n) - 1)) << bits;
        bits += n;
    }

    public void writeBitsMsb(int value, int n) {
        int rev = Integer.reverse(value) >>> (32 - n);
        container |= (long) (rev & ((1L << n) - 1)) << bits;
        bits += n;
    }

    public void flush() {
        while (bits >= 8) {
            data[pos++] = (byte) container;
            container >>>= 8;
            bits -= 8;
        }
    }

    public int close() {
        writeBits(1, 1); // end mark
        flush();
        if (bits > 0) {
            data[pos++] = (byte) container;
            container = 0;
            bits = 0;
        }
        return pos - start;
    }

    public int position() {
        return pos + (bits > 0 ? 1 : 0);
    }
}
