package com.onlikee.lightoss.exception;

/** A malformed or contract-incompatible Light OSS response. */
public final class LightOssProtocolException extends LightOssException {
    /** Creates a protocol exception. */
    public LightOssProtocolException(String message, String requestId) {
        super(message, requestId, null);
    }

    /** Creates a protocol exception with a cause. */
    public LightOssProtocolException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
