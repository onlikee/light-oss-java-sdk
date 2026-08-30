package com.onlikee.lightoss.exception;

/** Invalid client configuration or credential-provider behavior. */
public final class LightOssConfigurationException extends LightOssException {
    /** Creates a configuration exception. */
    public LightOssConfigurationException(String message) {
        super(message);
    }

    /** Creates a configuration exception associated with a request. */
    public LightOssConfigurationException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
