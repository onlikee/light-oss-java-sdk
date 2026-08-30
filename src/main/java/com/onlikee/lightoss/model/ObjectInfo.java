package com.onlikee.lightoss.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Object metadata returned by Light OSS.
 *
 * @param id object identifier
 * @param bucketName bucket name
 * @param objectKey object key
 * @param originalFilename original filename
 * @param size object size in bytes
 * @param contentType media type
 * @param etag entity tag
 * @param visibility object visibility
 * @param createdAt creation time
 * @param updatedAt last update time
 */
public record ObjectInfo(
        long id,
        String bucketName,
        String objectKey,
        String originalFilename,
        long size,
        String contentType,
        String etag,
        Visibility visibility,
        Instant createdAt,
        Instant updatedAt) {
    /** Creates immutable object metadata. */
    public ObjectInfo {
        bucketName = Objects.requireNonNull(bucketName, "bucketName");
        objectKey = Objects.requireNonNull(objectKey, "objectKey");
        originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
        contentType = Objects.requireNonNull(contentType, "contentType");
        etag = Objects.requireNonNull(etag, "etag");
        visibility = Objects.requireNonNull(visibility, "visibility");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
