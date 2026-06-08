package org.zstjd;

import org.zstjd.internal.Compressor;
import java.io.*;

public class ZstdOutputStream extends OutputStream {
    private final OutputStream out;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private int level;
    private boolean closed;

    public ZstdOutputStream(OutputStream out) { this(out, 3); }

    public ZstdOutputStream(OutputStream out, int level) {
        this.out = out;
        this.level = level;
    }

    @Override
    public void write(int b) throws IOException {
        buf.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        buf.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (buf.size() > 0) {
            Compressor c = new Compressor(level);
            byte[] compressed = c.compress(buf.toByteArray());
            out.write(compressed);
            buf.reset();
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        flush();
        out.close();
    }
}
