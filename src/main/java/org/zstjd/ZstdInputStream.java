package org.zstjd;

import org.zstjd.internal.Decompressor;
import java.io.*;

public class ZstdInputStream extends InputStream {
    private final InputStream in;
    private byte[] buf = new byte[4096];
    private int bufPos;
    private int bufLen;
    private boolean eof;
    private boolean closed;

    public ZstdInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == 1 ? (one[0] & 0xFF) : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("closed");
        if (len == 0) return 0;
        int copied = 0;
        while (copied < len) {
            if (bufPos >= bufLen) {
                if (eof) return copied > 0 ? copied : -1;
                byte[] newBuf = fillBuffer();
                if (newBuf == null) { eof = true; return copied > 0 ? copied : -1; }
                buf = newBuf;
                bufPos = 0;
                bufLen = buf.length;
            }
            int avail = bufLen - bufPos;
            int take = Math.min(avail, len - copied);
            System.arraycopy(buf, bufPos, b, off + copied, take);
            bufPos += take;
            copied += take;
        }
        return copied;
    }

    private byte[] fillBuffer() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) >= 0) {
            baos.write(tmp, 0, n);
        }
        byte[] compressed = baos.toByteArray();
        if (compressed.length == 0) return null;
        Decompressor d = new Decompressor();
        byte[] result = d.decompress(compressed);
        bufLen = result.length;
        return result;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        in.close();
    }
}
