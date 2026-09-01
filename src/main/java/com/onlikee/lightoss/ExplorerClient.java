package com.onlikee.lightoss;

import com.onlikee.lightoss.exception.LightOssProtocolException;
import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.EntryType;
import com.onlikee.lightoss.model.ExplorerEntry;
import com.onlikee.lightoss.model.Page;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.DownloadResponse;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Folder-tree and explorer-entry operations. */
public final class ExplorerClient {
    private final ClientContext context;

    ExplorerClient(ClientContext context) {
        this.context = context;
    }

    /** Lists all folder nodes in a bucket. */
    public LightOssResponse<List<FolderNode>> listFolders(String bucket) {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), foldersPath(bucket)),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.list(context.json(), data, "items", requestId, this::parseFolder));
    }

    /** Creates a logical folder marker. */
    public LightOssResponse<FolderNode> createFolder(CreateFolderRequest request) {
        Objects.requireNonNull(request, "request");
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), foldersPath(request.bucket())),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("prefix", request.prefix(), "name", request.name())),
                "application/json",
                Map.of(),
                201,
                this::parseFolder);
    }

    /** Deletes a folder, optionally recursively. */
    public LightOssResponse<Void> deleteFolder(DeleteFolderRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> query = Uris.query();
        query.put("path", request.path());
        query.put("recursive", request.recursive());
        return context.noContent(
                "DELETE",
                Uris.endpoint(context.baseUri(), foldersPath(request.bucket()), query),
                ClientContext.AuthMode.REQUIRED,
                Map.of(),
                204);
    }

    /** Streams a ZIP archive of a folder. */
    public DownloadResponse downloadFolderArchive(String bucket, String path) {
        Map<String, Object> query = Uris.query();
        query.put("path", Checks.rawText(path, "path"));
        return context.stream(
                "GET",
                Uris.endpoint(context.baseUri(), foldersPath(bucket) + "/archive", query),
                ClientContext.AuthMode.REQUIRED,
                Map.of(),
                200);
    }

    /** Lists one explicit page of directory and file entries. */
    public LightOssResponse<Page<ExplorerEntry>> listEntries(ListEntriesRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> query = Uris.query();
        query.put("prefix", request.prefix());
        query.put("search", request.search());
        query.put("limit", request.limit());
        query.put("cursor", request.cursor());
        query.put("sort_by", request.sortBy().value());
        query.put("sort_order", request.sortOrder().value());
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), entriesPath(request.bucket()), query),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.page(context.json(), data, requestId, this::parseEntry));
    }

    /** Deletes a batch while preserving the backend's per-item failure result. */
    public LightOssResponse<BatchDeleteResult> deleteEntries(String bucket, List<DeleteItem> items) {
        List<DeleteItem> checkedItems = Checks.list(items, 1, 200, "items");
        List<Map<String, String>> bodyItems = checkedItems.stream()
                .map(item -> Map.of("type", item.type().value(), "path", item.path()))
                .toList();
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), entriesPath(bucket) + "/batch-delete"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("items", bodyItems)),
                "application/json",
                Map.of(),
                200,
                this::parseBatchDelete);
    }

    private FolderNode parseFolder(JsonNode node, String requestId) {
        return new FolderNode(
                context.json().requiredText(node, "path", requestId),
                context.json().requiredText(node, "name", requestId),
                context.json().requiredText(node, "parent_path", requestId));
    }

    private ExplorerEntry parseEntry(JsonNode node, String requestId) {
        EntryType type = new EntryType(context.json().requiredText(node, "type", requestId));
        String path = context.json().requiredText(node, "path", requestId);
        String name = context.json().requiredText(node, "name", requestId);
        if (type.equals(EntryType.DIRECTORY)) {
            JsonNode empty = node.get("is_empty");
            if (empty == null || !empty.isBoolean()) {
                throw new LightOssProtocolException("directory entry does not contain boolean is_empty", requestId);
            }
            return new ExplorerEntry.DirectoryEntry(path, name, empty.booleanValue());
        }
        if (type.equals(EntryType.FILE)) {
            return new ExplorerEntry.FileEntry(
                    path,
                    name,
                    context.json().requiredText(node, "object_key", requestId),
                    context.json().requiredText(node, "original_filename", requestId),
                    context.json().requiredLong(node, "size", requestId),
                    context.json().requiredText(node, "content_type", requestId),
                    context.json().requiredText(node, "etag", requestId),
                    new Visibility(context.json().requiredText(node, "visibility", requestId)),
                    context.json().requiredInstant(node, "created_at", requestId),
                    context.json().requiredInstant(node, "updated_at", requestId));
        }
        return new ExplorerEntry.UnknownEntry(type, path, name);
    }

    private BatchDeleteResult parseBatchDelete(JsonNode data, String requestId) {
        List<BatchDeleteFailure> failures = Parsers.list(
                context.json(), data, "failed_items", requestId,
                (node, id) -> new BatchDeleteFailure(
                        new EntryType(context.json().requiredText(node, "type", id)),
                        context.json().requiredText(node, "path", id),
                        context.json().requiredText(node, "code", id),
                        context.json().requiredText(node, "message", id)));
        return new BatchDeleteResult(
                context.json().requiredInt(data, "deleted_count", requestId),
                context.json().requiredInt(data, "failed_count", requestId),
                failures);
    }

    private static String foldersPath(String bucket) {
        return "/api/v1/buckets/" + Uris.segment(bucket) + "/folders";
    }

    private static String entriesPath(String bucket) {
        return "/api/v1/buckets/" + Uris.segment(bucket) + "/entries";
    }

    /**
     * Folder-tree node metadata.
     *
     * @param path folder path
     * @param name folder name
     * @param parentPath parent folder path
     */
    public record FolderNode(String path, String name, String parentPath) {
        /** Creates a folder node. */
        public FolderNode {
            path = Objects.requireNonNull(path, "path");
            name = Objects.requireNonNull(name, "name");
            parentPath = Objects.requireNonNull(parentPath, "parentPath");
        }
    }

    /**
     * Request for creating a folder.
     *
     * @param bucket bucket name
     * @param prefix parent prefix
     * @param name folder name
     */
    public record CreateFolderRequest(String bucket, String prefix, String name) {
        /** Creates a validated request. */
        public CreateFolderRequest {
            bucket = Checks.rawText(bucket, "bucket");
            prefix = prefix == null ? "" : prefix;
            name = Checks.rawText(name, "name");
        }
    }

    /**
     * Request for deleting a folder.
     *
     * @param bucket bucket name
     * @param path folder path
     * @param recursive whether to delete non-empty contents
     */
    public record DeleteFolderRequest(String bucket, String path, boolean recursive) {
        /** Creates a validated request. */
        public DeleteFolderRequest {
            bucket = Checks.rawText(bucket, "bucket");
            path = Checks.rawText(path, "path");
        }
    }

    /** Supported explorer sort field. */
    public enum SortBy {
        /** Sort by name. */
        NAME("name"),
        /** Sort by size. */
        SIZE("size"),
        /** Sort by creation time. */
        CREATED_AT("created_at");

        private final String value;

        SortBy(String value) {
            this.value = value;
        }

        /** Returns the wire value. */
        public String value() {
            return value;
        }
    }

    /** Supported explorer sort direction. */
    public enum SortOrder {
        /** Ascending order. */
        ASC("asc"),
        /** Descending order. */
        DESC("desc");

        private final String value;

        SortOrder(String value) {
            this.value = value;
        }

        /** Returns the wire value. */
        public String value() {
            return value;
        }
    }

    /**
     * Explicit request for one explorer page.
     *
     * @param bucket bucket name
     * @param prefix folder prefix
     * @param search search text
     * @param limit page size
     * @param cursor continuation cursor
     * @param sortBy sort field
     * @param sortOrder sort direction
     */
    public record ListEntriesRequest(
            String bucket,
            String prefix,
            String search,
            int limit,
            String cursor,
            SortBy sortBy,
            SortOrder sortOrder) {
        /** Creates a validated request. */
        public ListEntriesRequest {
            bucket = Checks.rawText(bucket, "bucket");
            prefix = prefix == null ? "" : prefix;
            search = search == null ? "" : search;
            limit = Checks.range(limit, 1, 200, "limit");
            cursor = cursor == null ? "" : cursor;
            sortBy = Objects.requireNonNull(sortBy, "sortBy");
            sortOrder = Objects.requireNonNull(sortOrder, "sortOrder");
        }

        /** Creates a builder with backend-compatible defaults. */
        public static Builder builder(String bucket) {
            return new Builder(bucket);
        }

        /** Builder for {@link ListEntriesRequest}. */
        public static final class Builder {
            private final String bucket;
            private String prefix = "";
            private String search = "";
            private int limit = 100;
            private String cursor = "";
            private SortBy sortBy = SortBy.CREATED_AT;
            private SortOrder sortOrder = SortOrder.DESC;

            private Builder(String bucket) {
                this.bucket = bucket;
            }

            /** Filters by folder prefix. */
            public Builder prefix(String prefix) { this.prefix = prefix; return this; }
            /** Filters by a search string. */
            public Builder search(String search) { this.search = search; return this; }
            /** Sets a page size from 1 to 200. */
            public Builder limit(int limit) { this.limit = limit; return this; }
            /** Continues from an explicit cursor. */
            public Builder cursor(String cursor) { this.cursor = cursor; return this; }
            /** Sets the sort field. */
            public Builder sortBy(SortBy sortBy) { this.sortBy = sortBy; return this; }
            /** Sets the sort direction. */
            public Builder sortOrder(SortOrder sortOrder) { this.sortOrder = sortOrder; return this; }
            /** Builds the request. */
            public ListEntriesRequest build() {
                return new ListEntriesRequest(bucket, prefix, search, limit, cursor, sortBy, sortOrder);
            }
        }
    }

    /**
     * One requested explorer deletion.
     *
     * @param type file or directory type
     * @param path entry path
     */
    public record DeleteItem(EntryType type, String path) {
        /** Creates a validated delete item. */
        public DeleteItem {
            type = Objects.requireNonNull(type, "type");
            if (!type.isRequestValue()) {
                throw new LightOssValidationException("type must be file or directory");
            }
            path = Checks.rawText(path, "path");
        }
    }

    /**
     * One explorer batch-delete failure.
     *
     * @param type entry type
     * @param path entry path
     * @param code backend failure code
     * @param message backend failure message
     */
    public record BatchDeleteFailure(EntryType type, String path, String code, String message) {
        /** Creates an immutable failure. */
        public BatchDeleteFailure {
            type = Objects.requireNonNull(type, "type");
            path = Objects.requireNonNull(path, "path");
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Explorer batch-delete result including per-item failures.
     *
     * @param deletedCount deleted item count
     * @param failedCount failed item count
     * @param failedItems per-item failures
     */
    public record BatchDeleteResult(int deletedCount, int failedCount, List<BatchDeleteFailure> failedItems) {
        /** Creates an immutable result. */
        public BatchDeleteResult {
            failedItems = List.copyOf(failedItems);
        }
    }
}
