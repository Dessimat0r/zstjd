package org.zstjd;

public class ZstdException extends RuntimeException {
    public ZstdException(String msg) { super(msg); }
    public ZstdException(String msg, Throwable cause) { super(msg, cause); }
}
