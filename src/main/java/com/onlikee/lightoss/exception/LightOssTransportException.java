package com.onlikee.lightoss.exception;

/** A network or interrupted I/O failure. */
public class LightOssTransportException extends LightOssException {
    /** Creates a transport exception. */
    public LightOssTransportException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
