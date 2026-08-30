package com.onlikee.lightoss.model;

import java.util.List;
import java.util.Optional;

/**
 * One cursor-based result page.
 *
 * @param items immutable items in this page
 * @param nextCursor cursor for the next page, if one exists
 * @param <T> item type
 */
public record Page<T>(List<T> items, Optional<String> nextCursor) {
    /** Creates an immutable page. */
    public Page {
        items = List.copyOf(items);
        nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
    }
}
