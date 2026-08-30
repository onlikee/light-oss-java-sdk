package com.onlikee.lightoss.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One explorer entry. Known directory and file variants are modeled separately while unknown
 * future entry types retain their wire value.
 */
public sealed interface ExplorerEntry permits ExplorerEntry.DirectoryEntry, ExplorerEntry.FileEntry, ExplorerEntry.UnknownEntry {
    /** Returns the forward-compatible entry type. */
    EntryType type();

    /** Returns the entry path relative to the bucket root. */
    String path();

    /** Returns the display name. */
    String name();

    /**
     * A directory entry.
     *
     * @param path directory path
     * @param name display name
     * @param empty whether the directory has no entries
     */
    record DirectoryEntry(String path, String name, boolean empty) implements ExplorerEntry {
        /** Creates a directory entry. */
        public DirectoryEntry {
            path = Objects.requireNonNull(path, "path");
            name = Objects.requireNonNull(name, "name");
        }

        @Override
        public EntryType type() {
            return EntryType.DIRECTORY;
        }
    }

    /**
     * A file entry with object metadata.
     *
     * @param path explorer path
     * @param name display name
     * @param objectKey object key
     * @param originalFilename original filename
     * @param size size in bytes
     * @param contentType media type
     * @param etag entity tag
     * @param visibility object visibility
     * @param createdAt creation time
     * @param updatedAt last update time
     */
    record FileEntry(
            String path,
            String name,
            String objectKey,
            String originalFilename,
            long size,
            String contentType,
            String etag,
            Visibility visibility,
            Instant createdAt,
            Instant updatedAt) implements ExplorerEntry {
        /** Creates a file entry. */
        public FileEntry {
            path = Objects.requireNonNull(path, "path");
            name = Objects.requireNonNull(name, "name");
            objectKey = Objects.requireNonNull(objectKey, "objectKey");
            originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
            contentType = Objects.requireNonNull(contentType, "contentType");
            etag = Objects.requireNonNull(etag, "etag");
            visibility = Objects.requireNonNull(visibility, "visibility");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public EntryType type() {
            return EntryType.FILE;
        }
    }

    /**
     * An entry type introduced by a newer backend.
     *
     * @param type unknown forward-compatible type
     * @param path entry path
     * @param name display name
     */
    record UnknownEntry(EntryType type, String path, String name) implements ExplorerEntry {
        /** Creates an unknown entry while preserving its type value. */
        public UnknownEntry {
            type = Objects.requireNonNull(type, "type");
            path = Objects.requireNonNull(path, "path");
            name = Objects.requireNonNull(name, "name");
        }
    }
}
