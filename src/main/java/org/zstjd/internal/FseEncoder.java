package org.zstjd.internal;

public final class FseEncoder {
    private final FseTable table;

    public FseEncoder(FseTable table) {
        this.table = table;
    }

    public int begin(int symbol) {
        for (int s = 0; s < table.tableSize; s++)
            if ((table.symbol[s] & 0xFFFF) == symbol) return s;
        return 0;
    }

    public int encode(BitStream stream, int state, int symbol) {
        for (int s = 0; s < table.tableSize; s++) {
            if ((table.symbol[s] & 0xFFFF) == symbol) {
                int nBits = table.numBits[s] & 0xFF;
                int base = table.newState[s] & 0xFFFF;
                int bitsVal = state - base;
                if (bitsVal >= 0 && bitsVal < (1 << nBits)) {
                    stream.writeBits(bitsVal, nBits);
                    return s;
                }
            }
        }
        int bestS = 0;
        long bestScore = Long.MAX_VALUE;
        for (int s = 0; s < table.tableSize; s++) {
            if ((table.symbol[s] & 0xFFFF) == symbol) {
                int nb = table.numBits[s] & 0xFF;
                int bs = table.newState[s] & 0xFFFF;
                int bitsVal = state - bs;
                if (bitsVal < 0) {
                    long score = (long)(-bitsVal) + ((long)1 << nb);
                    if (score < bestScore) { bestScore = score; bestS = s; }
                } else if (bitsVal >= (1 << nb)) {
                    long score = (long)(bitsVal - (1 << nb) + 1);
                    if (score < bestScore) { bestScore = score; bestS = s; }
                }
            }
        }
        int nb = table.numBits[bestS] & 0xFF;
        int bs = table.newState[bestS] & 0xFFFF;
        int clamped = Math.max(0, Math.min(state - bs, (1 << nb) - 1));
        stream.writeBits(clamped, nb);
        return bestS;
    }

    public void finish(BitStream stream, int state) {
        stream.writeBits(state, table.accuracyLog);
        stream.flush();
    }
}
