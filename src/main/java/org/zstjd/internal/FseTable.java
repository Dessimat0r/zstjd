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
        int remaining = size + 1;
        int[] count = new int[maxSym + 1];
        for (int s = 0; s <= maxSym && remaining > 1; s++) {
            int bits = 32 - Integer.numberOfLeadingZeros(remaining - 1);
            int threshold = (1 << bits) - remaining;
            int val = fr.read(bits);
            int cnt;
            if (val < threshold) cnt = val;
            else cnt = val + fr.read(1) - threshold;
            cnt++;
            count[s] = cnt;
            remaining -= cnt;
        }
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
