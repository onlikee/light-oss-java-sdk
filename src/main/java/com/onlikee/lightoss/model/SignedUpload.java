package com.onlikee.lightoss.model;

import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.Uris;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A temporary authorization for one object PUT upload.
 *
 * @param method HTTP method, currently always PUT
 * @param path relative object path containing the signed token
 * @param headers exact request headers required by the authorization
 * @param expiresAt authorization expiry time
 */
public record SignedUpload(String method, URI path, Map<String, String> headers, Instant expiresAt) {
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "host", "content-length",
            "connection", "expect", "upgrade", "transfer-encoding", "trailer");

    /** Creates and validates a signed-upload result. */
    public SignedUpload {
        method = Checks.text(method, "method");
        if (!method.equals("PUT")) {
            throw new LightOssValidationException("signed upload method must be PUT");
        }
        path = Uris.requireSignedObjectPath(path);
        Objects.requireNonNull(headers, "headers");
        if (headers.isEmpty()) {
            throw new LightOssValidationException("signed upload headers must not be empty");
        }
        Map<String, String> validated = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "header name");
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || RESERVED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new LightOssValidationException("invalid signed upload header: " + name);
            }
            String value = Objects.requireNonNull(entry.getValue(), name);
            if (value.chars().anyMatch(character -> (character < 32 && character != '\t') || character == 127)) {
                throw new LightOssValidationException("invalid signed upload header value: " + name);
            }
            if (validated.putIfAbsent(name, value) != null) {
                throw new LightOssValidationException("duplicate signed upload header: " + name);
            }
        }
        headers = Map.copyOf(validated);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
