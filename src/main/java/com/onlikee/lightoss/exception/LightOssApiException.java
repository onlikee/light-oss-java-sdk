package com.onlikee.lightoss.exception;

import java.util.Objects;

/** A non-success HTTP response returned by Light OSS. */
public final class LightOssApiException extends LightOssException {
    private final int statusCode;
    private final String code;
    private final String serviceMessage;

    /** Creates an API exception. */
    public LightOssApiException(
            int statusCode,
            String code,
            String serviceMessage,
            String requestId) {
        super("Light OSS request failed with HTTP " + statusCode + " (" + code + "): " + serviceMessage, requestId, null);
        this.statusCode = statusCode;
        this.code = Objects.requireNonNull(code, "code");
        this.serviceMessage = Objects.requireNonNull(serviceMessage, "serviceMessage");
    }

    /** Returns the HTTP status code. */
    public int statusCode() {
        return statusCode;
    }

    /** Returns the backend error code, or {@code sdk_http_error} for a bodyless or non-JSON HTTP error. */
    public String code() {
        return code;
    }

    /** Returns the service error message. */
    public String serviceMessage() {
        return serviceMessage;
    }
}
