package org.zstjd.internal;

/** Backward bitstream reader (read from end of buffer, MSB of each byte first). */
public final class BitReader {
    private final byte[] data;
    private final int base;
    public long bits;
    public int bitsLeft;

    public BitReader(byte[] data, int base) {
        this.data = data;
        this.base = base;
    }

    public void load(long bitOffset, int n) {
        bits = 0;
        bitsLeft = n;
        for (int i = 0; i < n; i++) {
            long p = bitOffset + i;
            if (p < 0) { bits = (bits << 1); continue; }
            int bi = (int)(p / 8);
            if (bi >= data.length - base) { bits = (bits << 1); continue; }
            bits = (bits << 1) | ((data[base + bi] >> ((int)(p % 8))) & 1);
        }
    }

    public int read(int n) {
        if (n > bitsLeft) throw new IllegalArgumentException("want " + n + " bits, have " + bitsLeft);
        int val = (int)(bits >> (bitsLeft - n));
        bitsLeft -= n;
        return val;
    }
}
