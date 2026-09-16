package com.onlikee.lightoss.model;

import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.internal.Checks;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    private static final java.util.List<String> REQUIRED_HEADERS = java.util.List.of(
            "Content-Type", "X-Allow-Overwrite", "X-Object-Visibility", "X-Original-Filename");

    /** Creates and validates a signed-upload result. */
    public SignedUpload {
        method = Checks.text(method, "method");
        if (!method.equals("PUT")) {
            throw new LightOssValidationException("signed upload method must be PUT");
        }
        path = Objects.requireNonNull(path, "path");
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getRawFragment() != null
                || path.getRawPath() == null || !path.getRawPath().startsWith("/api/v1/buckets/")
                || !path.getRawPath().contains("/objects/") || !hasToken(path)) {
            throw new LightOssValidationException("signed upload path must be a relative Light OSS object path");
        }
        Objects.requireNonNull(headers, "headers");
        Map<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        if (caseInsensitive.size() != REQUIRED_HEADERS.size()) {
            throw new LightOssValidationException("signed upload headers are invalid");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String name : REQUIRED_HEADERS) {
            String value = caseInsensitive.get(name);
            if (value == null) {
                throw new LightOssValidationException("signed upload response is missing header " + name);
            }
            normalized.put(name, Checks.headerValue(value, name));
        }
        headers = Map.copyOf(normalized);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
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
