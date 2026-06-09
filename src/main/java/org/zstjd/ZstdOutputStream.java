package org.zstjd;

import org.zstjd.internal.Compressor;
import java.io.*;
import java.util.Arrays;

public class ZstdOutputStream extends OutputStream {
    private final OutputStream out;
    private byte[] buf = new byte[8192];
    private int bufPos;
    private final int level;
    private boolean closed;
    private final Compressor compressor;

    public ZstdOutputStream(OutputStream out) { this(out, 3); }

    public ZstdOutputStream(OutputStream out, int level) {
        this.out = out;
        this.level = level;
        this.compressor = new Compressor(level);
    }

    @Override
    public void write(int b) throws IOException {
        if (bufPos >= buf.length)
            buf = Arrays.copyOf(buf, buf.length * 2);
        buf[bufPos++] = (byte)b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (bufPos + len > buf.length)
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, bufPos + len));
        System.arraycopy(b, off, buf, bufPos, len);
        bufPos += len;
    }

    @Override
    public void flush() throws IOException {
        if (bufPos > 0) {
            compressor.reset(level);
            byte[] compressed = compressor.compress(Arrays.copyOf(buf, bufPos));
            out.write(compressed);
            bufPos = 0;
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (bufPos > 0) {
            compressor.reset(level);
            byte[] compressed = compressor.compress(Arrays.copyOf(buf, bufPos));
            out.write(compressed);
            bufPos = 0;
        }
        out.close();
    }
}
