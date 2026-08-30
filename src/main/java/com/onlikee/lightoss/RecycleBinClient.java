package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.EntryType;
import com.onlikee.lightoss.model.Page;
import com.onlikee.lightoss.model.Visibility;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Recycle-bin listing, restore, and permanent-deletion operations. */
public final class RecycleBinClient {
    private static final String PATH = "/api/v1/recycle-bin/objects";
    private final ClientContext context;

    RecycleBinClient(ClientContext context) {
        this.context = context;
    }

    /** Lists one explicit page of recycle-bin items. */
    public LightOssResponse<Page<RecycleBinItem>> list(ListRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> query = Uris.query();
        query.put("bucket", request.bucket());
        query.put("limit", request.limit());
        query.put("cursor", request.cursor());
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), PATH, query),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.page(context.json(), data, requestId, this::parseItem));
    }

    /** Restores 1 to 200 recycle-bin item IDs and retains per-item failures. */
    public LightOssResponse<RestoreResult> restore(List<Long> itemIds) {
        List<Long> checked = itemIds(itemIds);
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), PATH + "/restore"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("item_ids", checked)),
                "application/json",
                Map.of(),
                200,
                this::parseRestore);
    }

    /** Permanently deletes 1 to 200 recycle-bin item IDs and retains per-item failures. */
    public LightOssResponse<DeleteResult> deletePermanently(List<Long> itemIds) {
        List<Long> checked = itemIds(itemIds);
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), PATH + "/batch-delete"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("item_ids", checked)),
                "application/json",
                Map.of(),
                200,
                this::parseDelete);
    }

    private RecycleBinItem parseItem(JsonNode node, String requestId) {
        return new RecycleBinItem(
                context.json().requiredLong(node, "id", requestId),
                new EntryType(context.json().requiredText(node, "type", requestId)),
                context.json().requiredText(node, "bucket_name", requestId),
                context.json().requiredText(node, "path", requestId),
                context.json().requiredText(node, "name", requestId),
                context.json().requiredText(node, "object_key", requestId),
                context.json().requiredText(node, "original_filename", requestId),
                context.json().requiredLong(node, "size", requestId),
                context.json().requiredText(node, "content_type", requestId),
                context.json().requiredText(node, "etag", requestId),
                new Visibility(context.json().requiredText(node, "visibility", requestId)),
                context.json().requiredInstant(node, "created_at", requestId),
                context.json().requiredInstant(node, "deleted_at", requestId));
    }

    private RestoreResult parseRestore(JsonNode data, String requestId) {
        return new RestoreResult(
                context.json().requiredInt(data, "restored_count", requestId),
                context.json().requiredInt(data, "failed_count", requestId),
                failures(data, requestId));
    }

    private DeleteResult parseDelete(JsonNode data, String requestId) {
        return new DeleteResult(
                context.json().requiredInt(data, "deleted_count", requestId),
                context.json().requiredInt(data, "failed_count", requestId),
                failures(data, requestId));
    }

    private List<Failure> failures(JsonNode data, String requestId) {
        return Parsers.list(context.json(), data, "failed_items", requestId,
                (node, id) -> new Failure(
                        context.json().requiredLong(node, "id", id),
                        context.json().requiredText(node, "bucket_name", id),
                        context.json().requiredText(node, "path", id),
                        context.json().requiredText(node, "code", id),
                        context.json().requiredText(node, "message", id)));
    }

    private static List<Long> itemIds(List<Long> itemIds) {
        List<Long> checked = Checks.list(itemIds, 1, 200, "itemIds");
        for (Long itemId : checked) {
            Checks.positive(Objects.requireNonNull(itemId, "itemIds contains null"), "itemId");
        }
        return checked;
    }

    /**
     * Explicit request for one recycle-bin page.
     *
     * @param bucket optional bucket filter
     * @param limit page size
     * @param cursor continuation cursor
     */
    public record ListRequest(String bucket, int limit, String cursor) {
        /** Creates a validated request. An empty bucket includes every bucket. */
        public ListRequest {
            bucket = bucket == null ? "" : bucket.trim();
            limit = Checks.range(limit, 1, 100, "limit");
            cursor = cursor == null ? "" : cursor.trim();
        }

        /** Creates a builder with a default page size of 100. */
        public static Builder builder() {
            return new Builder();
        }

        /** Builder for {@link ListRequest}. */
        public static final class Builder {
            private String bucket = "";
            private int limit = 100;
            private String cursor = "";

            private Builder() {
            }

            /** Restricts results to one bucket. */
            public Builder bucket(String bucket) { this.bucket = bucket; return this; }
            /** Sets a page size from 1 to 100. */
            public Builder limit(int limit) { this.limit = limit; return this; }
            /** Continues from an explicit cursor. */
            public Builder cursor(String cursor) { this.cursor = cursor; return this; }
            /** Builds the request. */
            public ListRequest build() { return new ListRequest(bucket, limit, cursor); }
        }
    }

    /**
     * Recycle-bin object or logical directory metadata.
     *
     * @param id recycle-bin identifier
     * @param type logical item type
     * @param bucketName bucket name
     * @param path logical path
     * @param name display name
     * @param objectKey object key
     * @param originalFilename original filename
     * @param size size in bytes
     * @param contentType media type
     * @param etag entity tag
     * @param visibility original visibility
     * @param createdAt object creation time
     * @param deletedAt deletion time
     */
    public record RecycleBinItem(
            long id,
            EntryType type,
            String bucketName,
            String path,
            String name,
            String objectKey,
            String originalFilename,
            long size,
            String contentType,
            String etag,
            Visibility visibility,
            Instant createdAt,
            Instant deletedAt) {
        /** Creates an immutable recycle-bin item. */
        public RecycleBinItem {
            type = Objects.requireNonNull(type, "type");
            bucketName = Objects.requireNonNull(bucketName, "bucketName");
            path = Objects.requireNonNull(path, "path");
            name = Objects.requireNonNull(name, "name");
            objectKey = Objects.requireNonNull(objectKey, "objectKey");
            originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
            contentType = Objects.requireNonNull(contentType, "contentType");
            etag = Objects.requireNonNull(etag, "etag");
            visibility = Objects.requireNonNull(visibility, "visibility");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
        }
    }

    /**
     * One restore or permanent-delete failure.
     *
     * @param id recycle-bin identifier
     * @param bucketName bucket name
     * @param path logical path
     * @param code backend failure code
     * @param message backend failure message
     */
    public record Failure(long id, String bucketName, String path, String code, String message) {
        /** Creates an immutable failure. */
        public Failure {
            bucketName = Objects.requireNonNull(bucketName, "bucketName");
            path = Objects.requireNonNull(path, "path");
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Batch restore result.
     *
     * @param restoredCount restored item count
     * @param failedCount failed item count
     * @param failedItems per-item failures
     */
    public record RestoreResult(int restoredCount, int failedCount, List<Failure> failedItems) {
        /** Creates an immutable result. */
        public RestoreResult { failedItems = List.copyOf(failedItems); }
    }

    /**
     * Batch permanent-delete result.
     *
     * @param deletedCount deleted item count
     * @param failedCount failed item count
     * @param failedItems per-item failures
     */
    public record DeleteResult(int deletedCount, int failedCount, List<Failure> failedItems) {
        /** Creates an immutable result. */
        public DeleteResult { failedItems = List.copyOf(failedItems); }
    }
}
