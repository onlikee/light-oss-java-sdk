package com.onlikee.lightoss.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Explorer or recycle-bin entry type with forward-compatible unknown values.
 *
 * @param value wire value
 */
public record EntryType(String value) {
    /** Directory entry. */
    public static final EntryType DIRECTORY = new EntryType("directory");
    /** File entry. */
    public static final EntryType FILE = new EntryType("file");

    /** Creates an entry type from its wire value. */
    public EntryType {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("entry type must not be blank");
        }
    }

    /** Returns whether this value is accepted in a batch-delete request. */
    public boolean isRequestValue() {
        return equals(DIRECTORY) || equals(FILE);
    }

    @Override
    public String toString() {
        return value;
    }
}
