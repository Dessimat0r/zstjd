package org.zstjd.internal;

public final class FseEncoder {
    private final short[] nextState;
    private final int[] deltaBits;
    private final int[] deltaState;
    private final int tableLog;

    public FseEncoder(int tableLog, int maxSymbol) {
        this.tableLog = tableLog;
        this.nextState = new short[1 << tableLog];
        this.deltaBits = new int[maxSymbol + 1];
        this.deltaState = new int[maxSymbol + 1];
    }

    public void init(int[] dist, int maxSym) {
        int size = 1 << tableLog;
        byte[] table = new byte[size];
        int high = size - 1;
        int[] cum = new int[maxSym + 2];

        for (int i = 0; i <= maxSym; i++) {
            int cnt = i < dist.length ? dist[i] : 0;
            if (cnt == -1) {
                cum[i + 1] = cum[i] + 1;
                table[high--] = (byte) i;
            } else {
                cum[i + 1] = cum[i] + Math.max(cnt, 0);
            }
        }
        cum[maxSym + 1] = size + 1;

        int step = (size >> 1) + (size >> 3) + 3;
        int pos = 0;
        for (int s = 0; s <= maxSym; s++) {
            int cnt = s < dist.length ? dist[s] : 0;
            if (cnt <= 0) continue;
            for (int i = 0; i < cnt; i++) {
                table[pos] = (byte) s;
                do { pos = (pos + step) & (size - 1); } while (pos > high);
            }
        }

        for (int i = 0; i < size; i++) {
            int sym = table[i] & 0xFF;
            nextState[cum[sym]++] = (short) (size + i);
        }

        int total = 0;
        for (int sym = 0; sym <= maxSym; sym++) {
            int cnt = sym < dist.length ? dist[sym] : 0;
            if (cnt == 0) {
                deltaBits[sym] = ((tableLog + 1) << 16) - size;
            } else if (cnt == -1 || cnt == 1) {
                deltaBits[sym] = (tableLog << 16) - size;
                deltaState[sym] = total - 1;
                total++;
            } else {
                int maxBits = tableLog - (31 - Integer.numberOfLeadingZeros(cnt - 1));
                int minState = cnt << maxBits;
                deltaBits[sym] = (maxBits << 16) - minState;
                deltaState[sym] = total - cnt;
                total += cnt;
            }
        }
    }

    public int begin(int symbol) {
        int bits = (deltaBits[symbol] + (1 << 15)) >>> 16;
        int base = ((bits << 16) - deltaBits[symbol]) >>> bits;
        return nextState[base + deltaState[symbol]] & 0xFFFF;
    }

    public int encode(BitStream stream, int state, int symbol) {
        int bits = (state + deltaBits[symbol]) >>> 16;
        stream.write(state, bits);
        return nextState[(state >>> bits) + deltaState[symbol]] & 0xFFFF;
    }

    public void finish(BitStream stream, int state) {
        stream.writeBits(state, tableLog);
        stream.flush();
    }
}
