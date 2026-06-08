package org.zstjd.internal;

/** Forward bitstream reader for FSE table headers (LSB first within each byte). */
public final class ForwardReader {
    private final byte[] data;
    private int pos;
    private int bitOff;

    public ForwardReader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    public int read(int n) {
        int val = 0, shift = 0;
        while (n > 0) {
            int avail = Math.min(n, 8 - bitOff);
            val |= ((data[pos] >> bitOff) & ((1 << avail) - 1)) << shift;
            shift += avail;
            bitOff += avail;
            n -= avail;
            if (bitOff == 8) { pos++; bitOff = 0; }
        }
        return val;
    }

    public int bytePos() {
        return bitOff > 0 ? pos + 1 : pos;
    }

    public void align() { if (bitOff > 0) { pos++; bitOff = 0; } }
}
