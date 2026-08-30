package com.onlikee.lightoss.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Rate-limit backend with forward-compatible unknown values.
 *
 * @param value wire value
 */
public record RateLimitBackend(String value) {
    /** Process-local rate limiting. */
    public static final RateLimitBackend LOCAL = new RateLimitBackend("local");
    /** MySQL-coordinated rate limiting. */
    public static final RateLimitBackend MYSQL = new RateLimitBackend("mysql");

    /** Creates a limiter backend from its wire value. */
    public RateLimitBackend {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("rate-limit backend must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
