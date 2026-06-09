package org.zstjd;

public class ZstdException extends RuntimeException {
    public static final int GENERIC = 1;
    public static final int PREFIX_UNKNOWN = 2;
    public static final int CORRUPTION_DETECTED = 6;
    public static final int CHECKSUM_WRONG = 7;
    public static final int LITERALS_HEADER_WRONG = 8;
    public static final int DST_SIZE_TOO_SMALL = 20;

    private final int errorCode;

    public ZstdException(int errorCode, String msg) {
        super(msg + " (error " + errorCode + ")");
        this.errorCode = errorCode;
    }
    public ZstdException(String msg) { this(GENERIC, msg); }
    public ZstdException(int errorCode, String msg, Throwable cause) { super(msg + " (error " + errorCode + ")", cause); this.errorCode = errorCode; }
    public ZstdException(String msg, Throwable cause) { super(msg, cause); this.errorCode = GENERIC; }

    public int getErrorCode() { return errorCode; }
}
