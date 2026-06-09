package org.zstjd.internal;
import org.zstjd.ZstdException;

import java.util.Arrays;

public final class Dict {
    public final int id;
    public final byte[] content;
    public final FseTable ofTable, mlTable, llTable;
    public final short[] huffTable;
    public final byte[] huffNbBits;
    public final int huffTableLog;

    public static Dict load(byte[] data) {
        int pos = 0;
        int magic = Constants.readLE32(data, pos); pos += 4;
        if (magic != 0xEC30A437)
            throw new ZstdException(ZstdException.PREFIX_UNKNOWN, "Bad dict magic: 0x" + Integer.toHexString(magic));
        int dictId = Constants.readLE32(data, pos); pos += 4;

        int[] codeLen = new int[256];
        int hdrSize = Huff.readHuffHeader(data, pos, data.length - pos, codeLen);
        if (hdrSize <= 0) throw new ZstdException(ZstdException.LITERALS_HEADER_WRONG, "Bad dict huff header");
        pos += hdrSize;
        short[] huffTable = new short[1 << 12];
        byte[] huffNbBits = new byte[1 << 12];
        int huffTableLog = Huff.buildTable(codeLen, huffTable, huffNbBits);

        // Read 3 FSE tables using ForwardReader
        ForwardReader fr = new ForwardReader(data, pos);
        FseTable ofTable = FseTable.readFrom(fr, 28); fr.align();
        FseTable mlTable = FseTable.readFrom(fr, 52); fr.align();
        FseTable llTable = FseTable.readFrom(fr, 35); fr.align();
        int fseBytes = fr.bytePos() - pos;

        byte[] dictContent = Arrays.copyOfRange(data, pos + fseBytes, data.length);
        return new Dict(dictId, huffTable, huffNbBits, huffTableLog,
                       ofTable, mlTable, llTable, dictContent);
    }

    public static Dict fromTables(int dictId, short[] huffTbl, byte[] huffNB, int huffLog,
                                   FseTable ofTbl, FseTable mlTbl, FseTable llTbl,
                                   byte[] dictContent) {
        return new Dict(dictId, huffTbl, huffNB, huffLog, ofTbl, mlTbl, llTbl, dictContent);
    }

    private Dict(int dictId, short[] huffTbl, byte[] huffNB, int huffLog,
                 FseTable ofTbl, FseTable mlTbl, FseTable llTbl,
                 byte[] dictContent) {
        this.id = dictId;
        this.huffTable = huffTbl;
        this.huffNbBits = huffNB;
        this.huffTableLog = huffLog;
        this.ofTable = ofTbl;
        this.mlTable = mlTbl;
        this.llTable = llTbl;
        this.content = dictContent;
    }
}
