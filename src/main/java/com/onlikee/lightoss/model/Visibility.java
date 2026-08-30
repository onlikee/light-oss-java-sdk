package com.onlikee.lightoss.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Object visibility. Unknown response values are preserved for forward compatibility.
 *
 * @param value wire value
 */
public record Visibility(String value) {
    /** Public object visibility. */
    public static final Visibility PUBLIC = new Visibility("public");
    /** Private object visibility. */
    public static final Visibility PRIVATE = new Visibility("private");

    /** Creates a visibility from its wire value. */
    public Visibility {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("visibility value must not be blank");
        }
    }

    /** Returns whether this value is valid in a visibility request. */
    public boolean isRequestValue() {
        return equals(PUBLIC) || equals(PRIVATE);
    }

    @Override
    public String toString() {
        return value;
    }
}
