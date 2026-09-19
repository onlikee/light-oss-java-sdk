package com.onlikee.lightoss.model;

import com.onlikee.lightoss.internal.Uris;
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
        path = Uris.requireSignedObjectPath(path);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
