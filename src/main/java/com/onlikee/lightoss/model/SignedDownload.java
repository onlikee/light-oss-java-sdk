package com.onlikee.lightoss.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * A relative signed object-download path.
 *
 * @param path relative path containing signature query parameters
 * @param expiresAt signature expiry time
 */
public record SignedDownload(URI path, Instant expiresAt) {
    /** Creates a signed-download result. */
    public SignedDownload {
        path = Objects.requireNonNull(path, "path");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getRawFragment() != null
                || path.getRawPath() == null || !path.getRawPath().startsWith("/api/v1/buckets/")
                || !hasToken(path)) {
            throw new IllegalArgumentException("signed download path must be a relative Light OSS object path");
        }
    }

    private static boolean hasToken(URI path) {
        String query = path.getRawQuery();
        if (query == null) {
            return false;
        }
        return java.util.Arrays.stream(query.split("&"))
                .anyMatch(value -> value.startsWith("token=") && value.length() > "token=".length());
    }
}
