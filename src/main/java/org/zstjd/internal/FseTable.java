package org.zstjd.internal;

public final class FseTable {
    public final int accuracyLog;
    public final int tableSize;
    public final short[] symbol;     // [state] -> symbol
    public final byte[] numBits;     // [state] -> number of bits for transition
    public final short[] newState;   // [state] -> baseline for transition

    public FseTable(int accuracyLog) {
        this.accuracyLog = accuracyLog;
        this.tableSize = 1 << accuracyLog;
        this.symbol = new short[tableSize];
        this.numBits = new byte[tableSize];
        this.newState = new short[tableSize];
    }

    public static FseTable fromDist(int[] dist, int accLog, int maxSym) {
        int size = 1 << accLog;
        FseTable t = new FseTable(accLog);
        short[] stateDesc = new short[maxSym + 1];
        int high = size;
        for (int s = 0; s <= maxSym; s++) {
            int cnt = s < dist.length ? dist[s] : 0;
            if (cnt == -1) { t.symbol[--high] = (short)s; stateDesc[s] = 1; }
            else if (cnt > 0) stateDesc[s] = (short)cnt;
        }
        int step = (size >> 1) + (size >> 3) + 3, mask = size - 1, pos = 0;
        for (int s = 0; s <= maxSym; s++) {
            int cnt = s < dist.length ? dist[s] : 0;
            if (cnt <= 0) continue;
            for (int i = 0; i < cnt; i++) {
                t.symbol[pos] = (short)s;
                do { pos = (pos + step) & mask; } while (pos >= high);
            }
        }
        for (int i = 0; i < size; i++) {
            int sym = t.symbol[i] & 0xFFFF;
            int next = stateDesc[sym]++ & 0xFFFF;
            int nb = accLog - (31 - Integer.numberOfLeadingZeros(next));
            t.numBits[i] = (byte)nb;
            t.newState[i] = (short)((next << nb) - size);
        }
        return t;
    }

    public static FseTable readFrom(ForwardReader fr, int maxSym) {
        int accLog = fr.read(4) + 5;
        if (accLog > 15) throw new RuntimeException("FSE accuracyLog too large: " + accLog);
        int size = 1 << accLog;
        int[] count = new int[maxSym + 1];
        int remaining = size + 1, threshold = size, nbBits = accLog + 1;
        int charnum = 0;
        boolean previous0 = false;

        while (remaining > 1 && charnum <= maxSym) {
            if (previous0) {
                // Count zeros: read 2-bit repeat codes
                int repeats;
                do {
                    repeats = fr.read(2);
                    charnum += 3 * repeats;
                } while (repeats == 3 && charnum <= maxSym);
                if (charnum > maxSym) break;
                // No more zero-count symbols to skip
                previous0 = false;
                if (remaining <= 1) break;
            }

            int max = (2 * threshold - 1) - remaining;
            int cnt;
            if (max >= 0) {
                int val = fr.read(nbBits - 1);
                if (val >= max) {
                    int extra = fr.read(1);
                    cnt = (val << 1) + extra - max;
                } else {
                    cnt = val;
                }
            } else {
                cnt = fr.read(nbBits);
            }
            cnt--; // adjust for extra accuracy
            if (cnt >= 0) {
                remaining -= cnt;
            } else {
                // cnt == -1 means zero-count
                previous0 = true;
            }
            count[charnum++] = cnt;

            if (remaining < threshold) {
                nbBits = 32 - Integer.numberOfLeadingZeros(remaining) + 1;
                threshold = 1 << (nbBits - 1);
            }
        }
        if (remaining != 1) throw new RuntimeException("Corrupt FSE table");

        FseTable t = new FseTable(accLog);
        short[] stateDesc = new short[maxSym + 1];
        int high = size;
        for (int s = 0; s <= maxSym; s++) {
            int cnt = count[s];
            if (cnt == -1) { t.symbol[--high] = (short)s; stateDesc[s] = 1; }
            else if (cnt > 0) stateDesc[s] = (short)cnt;
        }
        int step = (size >> 1) + (size >> 3) + 3, mask = size - 1, pos = 0;
        for (int s = 0; s <= maxSym; s++) {
            int cnt = count[s];
            if (cnt <= 0) continue;
            for (int i = 0; i < cnt; i++) {
                t.symbol[pos] = (short)s;
                do { pos = (pos + step) & mask; } while (pos >= high);
            }
        }
        for (int i = 0; i < size; i++) {
            int sym = t.symbol[i] & 0xFFFF;
            int next = stateDesc[sym]++ & 0xFFFF;
            int nb = accLog - (31 - Integer.numberOfLeadingZeros(next));
            t.numBits[i] = (byte)nb;
            t.newState[i] = (short)((next << nb) - size);
        }
        return t;
    }
}
