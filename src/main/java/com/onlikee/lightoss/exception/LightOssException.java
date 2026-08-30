package com.onlikee.lightoss.exception;

import java.util.Optional;

/** Base unchecked exception for Light OSS SDK failures. */
public class LightOssException extends RuntimeException {
    private final String requestId;

    /** Creates an SDK exception. */
    public LightOssException(String message) {
        this(message, null, null);
    }

    /** Creates an SDK exception with a cause. */
    public LightOssException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /** Creates an SDK exception with request context. */
    public LightOssException(String message, String requestId, Throwable cause) {
        super(message, cause);
        this.requestId = requestId;
    }

    /** Returns the request identifier when one was allocated. */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }
}
