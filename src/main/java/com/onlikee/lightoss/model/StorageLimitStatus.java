package com.onlikee.lightoss.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Storage-limit state with forward-compatible unknown values.
 *
 * @param value wire value
 */
public record StorageLimitStatus(String value) {
    /** Usage is below the warning threshold. */
    public static final StorageLimitStatus OK = new StorageLimitStatus("ok");
    /** Usage is near the configured limit. */
    public static final StorageLimitStatus WARNING = new StorageLimitStatus("warning");
    /** Usage exceeds the configured limit. */
    public static final StorageLimitStatus EXCEEDED = new StorageLimitStatus("exceeded");

    /** Creates a storage status from its wire value. */
    public StorageLimitStatus {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("storage limit status must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
