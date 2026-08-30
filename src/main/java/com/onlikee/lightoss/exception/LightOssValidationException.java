package com.onlikee.lightoss.exception;

/** A request violates a stable local SDK constraint. */
public final class LightOssValidationException extends LightOssException {
    /** Creates a validation exception. */
    public LightOssValidationException(String message) {
        super(message);
    }

    /** Creates a validation exception with a cause. */
    public LightOssValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
