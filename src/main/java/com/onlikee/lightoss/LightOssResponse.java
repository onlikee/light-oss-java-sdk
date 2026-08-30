package com.onlikee.lightoss;

import java.util.Objects;

/**
 * A successful Light OSS response together with its request identifier.
 *
 * @param data response data; {@code null} for no-content operations
 * @param requestId request identifier returned by Light OSS
 * @param <T> response data type
 */
public record LightOssResponse<T>(T data, String requestId) {
    /** Creates a successful response. */
    public LightOssResponse {
        requestId = Objects.requireNonNull(requestId, "requestId");
    }
}
