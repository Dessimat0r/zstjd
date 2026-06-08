package org.zstjd.internal;

public final class BitWriter {
    private byte[] data;
    private int bytePos;
    private int bitPos; // 0-7, MSB of current byte

    public BitWriter(byte[] data, int start) {
        this.data = data;
        this.bytePos = start;
    }

    public void writeBits(int value, int n) {
        while (n > 0) {
            if (bytePos >= data.length) break;
            int room = 8 - bitPos;
            int take = Math.min(n, room);
            data[bytePos] |= ((value >>> (n - take)) & ((1 << take) - 1)) << (room - take);
            bitPos += take;
            n -= take;
            if (bitPos == 8) { bytePos++; bitPos = 0; }
        }
    }

    public int getBytePos() { return bitPos > 0 ? bytePos + 1 : bytePos; }
}
