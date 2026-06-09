package org.zstjd.internal;

public final class FseEncoder {
    private final FseTable table;
    private final short[] nextState;      // encoding table: sorted by symbol
    private final int[] deltaBits;        // [symbol] -> encoded transition info
    private final int[] deltaState;       // [symbol] -> state offset

    public FseEncoder(FseTable table) {
        this.table = table;
        int size = table.tableSize;
        int maxSym = 0;
        for (int i = 0; i < size; i++) {
            int sym = table.symbol[i] & 0xFFFF;
            if (sym > maxSym) maxSym = sym;
        }
        this.nextState = new short[size];
        this.deltaBits = new int[maxSym + 1];
        this.deltaState = new int[maxSym + 1];
        buildEncodingTable();
    }

    private void buildEncodingTable() {
        int size = table.tableSize;
        int maxSym = deltaBits.length - 1;

        // Count occurrences of each symbol in the decoding table
        int[] counts = new int[maxSym + 1];
        for (int i = 0; i < size; i++) {
            int sym = table.symbol[i] & 0xFFFF;
            if (sym <= maxSym) counts[sym]++;
        }

        // Build cumulative positions
        int[] cumulative = new int[maxSym + 2];
        for (int s = 0; s <= maxSym; s++) {
            int cnt = counts[s];
            cumulative[s + 1] = cumulative[s] + (cnt > 0 ? cnt : 0);
        }
        cumulative[maxSym + 1] = size + 1;

        // Fill nextState: for each state in decoding table, record its position
        for (int i = 0; i < size; i++) {
            int sym = table.symbol[i] & 0xFFFF;
            if (sym > maxSym) continue;
            nextState[cumulative[sym]++] = (short) (size + i);
        }

        // Build symbol transformation table (deltaBits, deltaState)
        int total = 0;
        for (int sym = 0; sym <= maxSym; sym++) {
            int cnt = counts[sym];
            if (cnt == 0) {
                deltaBits[sym] = ((table.accuracyLog + 1) << 16) - size;
            } else if (cnt == 1) {
                deltaBits[sym] = (table.accuracyLog << 16) - size;
                deltaState[sym] = total - 1;
                total++;
            } else {
                int maxBits = table.accuracyLog - (31 - Integer.numberOfLeadingZeros(cnt - 1));
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
        stream.writeBits(state, bits);
        return nextState[(state >>> bits) + deltaState[symbol]] & 0xFFFF;
    }

    public void finish(BitStream stream, int state) {
        stream.writeBits(state, table.accuracyLog);
        stream.flush();
    }
}
