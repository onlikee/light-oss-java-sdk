package com.onlikee.lightoss.exception;

/** A request or connection timeout. */
public final class LightOssTimeoutException extends LightOssTransportException {
    /** Creates a timeout exception. */
    public LightOssTimeoutException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
