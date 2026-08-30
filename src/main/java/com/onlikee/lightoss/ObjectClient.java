package com.onlikee.lightoss;

import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.MultipartBody;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.SourceBodyPublishers;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.ObjectInfo;
import com.onlikee.lightoss.model.Page;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.ContentMetadata;
import com.onlikee.lightoss.transfer.DownloadResponse;
import com.onlikee.lightoss.transfer.UploadSource;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Object listing, transfer, deletion, and visibility operations. */
public final class ObjectClient {
    private final ClientContext context;

    ObjectClient(ClientContext context) {
        this.context = context;
    }

    /** Lists objects without performing automatic pagination. */
    public LightOssResponse<Page<ObjectInfo>> list(ListObjectsRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> query = Uris.query();
        query.put("prefix", request.prefix());
        query.put("limit", request.limit());
        query.put("cursor", request.cursor());
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), bucketPath(request.bucket()), query),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.page(context.json(), data, requestId,
                        (node, id) -> Parsers.object(context.json(), node, id)));
    }

    /** Uploads one object as a streamed request body. */
    public LightOssResponse<ObjectInfo> upload(UploadObjectRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequest.BodyPublisher body;
        try {
            body = SourceBodyPublishers.publisher(request.source());
        } catch (FileNotFoundException exception) {
            throw new LightOssValidationException("upload path is not readable", exception);
        }
        return context.json(
                "PUT",
                objectUri(request.bucket(), request.key()),
                ClientContext.AuthMode.REQUIRED,
                body,
                request.source().contentType(),
                Map.of(
                        "X-Object-Visibility", request.visibility().value(),
                        "X-Allow-Overwrite", Boolean.toString(request.allowOverwrite()),
                        "X-Original-Filename", Uris.encodeHeaderFilename(request.originalFilename())),
                201,
                (data, requestId) -> Parsers.object(context.json(), data, requestId));
    }

    /** Uploads 1 to 2000 objects in one backend-atomic multipart operation. */
    public LightOssResponse<BatchUploadResult> uploadBatch(UploadBatchRequest request) {
        Objects.requireNonNull(request, "request");
        MultipartBody multipart = new MultipartBody()
                .text("prefix", request.prefix())
                .text("visibility", request.visibility().value());
        List<Map<String, String>> manifest = new ArrayList<>(request.items().size());
        for (int index = 0; index < request.items().size(); index++) {
            UploadItem item = request.items().get(index);
            String field = "file_" + index;
            manifest.add(Map.of("file_field", field, "relative_path", item.relativePath()));
            multipart.file(field, item.source());
        }
        multipart.text("manifest", new String(context.json().write(manifest), java.nio.charset.StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), bucketPath(request.bucket()) + "/batch"),
                ClientContext.AuthMode.REQUIRED,
                multipart.publisher(),
                multipart.contentType(),
                Map.of("X-Allow-Overwrite", Boolean.toString(request.allowOverwrite())),
                201,
                this::parseBatchUpload);
    }

    /** Downloads an object from the API base URI, optionally using a bearer token. */
    public DownloadResponse download(DownloadObjectRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> query = Uris.query();
        query.put("download", request.forceDownload() ? "true" : null);
        return context.stream(
                "GET",
                Uris.endpoint(context.baseUri(), objectPath(request.bucket(), request.key()), query),
                ClientContext.AuthMode.OPTIONAL,
                Map.of(),
                200);
    }

    /** Reads object metadata, attaching Bearer only when one is configured. */
    public LightOssResponse<ContentMetadata> head(String bucket, String key) {
        return head(bucket, key, false);
    }

    /** Reads object metadata and optionally requests attachment content-disposition metadata. */
    public LightOssResponse<ContentMetadata> head(String bucket, String key, boolean forceDownload) {
        Map<String, Object> query = Uris.query();
        query.put("download", forceDownload ? "true" : null);
        return context.head(
                Uris.endpoint(context.baseUri(), objectPath(bucket, key), query),
                ClientContext.AuthMode.OPTIONAL,
                Map.of(),
                200);
    }

    /** Downloads an object through a relative path returned by {@link SigningClient}. */
    public DownloadResponse downloadSigned(URI signedPath) {
        return context.stream(
                "GET",
                Uris.signedPath(context.baseUri(), signedPath),
                ClientContext.AuthMode.NONE,
                Map.of(),
                200);
    }

    /** Soft-deletes an object. */
    public LightOssResponse<Void> delete(String bucket, String key) {
        return context.noContent(
                "DELETE", objectUri(bucket, key), ClientContext.AuthMode.REQUIRED, Map.of(), 204);
    }

    /** Updates an object's visibility. */
    public LightOssResponse<ObjectInfo> updateVisibility(String bucket, String key, Visibility visibility) {
        Visibility checkedVisibility = requestVisibility(visibility);
        return context.json(
                "PATCH",
                Uris.endpoint(context.baseUri(), bucketPath(bucket) + "/visibility/" + Uris.objectKey(key)),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("visibility", checkedVisibility.value())),
                "application/json",
                Map.of(),
                200,
                (data, requestId) -> Parsers.object(context.json(), data, requestId));
    }

    private BatchUploadResult parseBatchUpload(tools.jackson.databind.JsonNode data, String requestId) {
        return new BatchUploadResult(
                context.json().requiredInt(data, "uploaded_count", requestId),
                Parsers.list(context.json(), data, "items", requestId,
                        (node, id) -> Parsers.object(context.json(), node, id)));
    }

    private URI objectUri(String bucket, String key) {
        return Uris.endpoint(context.baseUri(), objectPath(bucket, key));
    }

    private static String objectPath(String bucket, String key) {
        return bucketPath(bucket) + "/" + Uris.objectKey(key);
    }

    private static String bucketPath(String bucket) {
        return "/api/v1/buckets/" + Uris.segment(bucket) + "/objects";
    }

    private static Visibility requestVisibility(Visibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        if (!visibility.isRequestValue()) {
            throw new LightOssValidationException("visibility must be public or private");
        }
        return visibility;
    }

    /**
     * Explicit request for one page of objects.
     *
     * @param bucket bucket name
     * @param prefix object-key prefix
     * @param limit page size
     * @param cursor continuation cursor
     */
    public record ListObjectsRequest(String bucket, String prefix, int limit, String cursor) {
        /** Creates a validated request. */
        public ListObjectsRequest {
            bucket = Checks.text(bucket, "bucket");
            prefix = prefix == null ? "" : prefix.trim();
            limit = Checks.range(limit, 1, 100, "limit");
            cursor = cursor == null ? "" : cursor.trim();
        }

        /** Creates a builder with a default page size of 100. */
        public static Builder builder(String bucket) {
            return new Builder(bucket);
        }

        /** Builder for {@link ListObjectsRequest}. */
        public static final class Builder {
            private final String bucket;
            private String prefix = "";
            private int limit = 100;
            private String cursor = "";

            private Builder(String bucket) {
                this.bucket = bucket;
            }

            /** Filters by object-key prefix. */
            public Builder prefix(String prefix) {
                this.prefix = prefix;
                return this;
            }

            /** Sets a page size from 1 to 100. */
            public Builder limit(int limit) {
                this.limit = limit;
                return this;
            }

            /** Continues from an explicit cursor. */
            public Builder cursor(String cursor) {
                this.cursor = cursor;
                return this;
            }

            /** Builds the request. */
            public ListObjectsRequest build() {
                return new ListObjectsRequest(bucket, prefix, limit, cursor);
            }
        }
    }

    /**
     * Request for a single streamed upload.
     *
     * @param bucket bucket name
     * @param key object key
     * @param source repeatable upload source
     * @param originalFilename original filename metadata
     * @param visibility requested visibility
     * @param allowOverwrite whether an existing key may be replaced
     */
    public record UploadObjectRequest(
            String bucket,
            String key,
            UploadSource source,
            String originalFilename,
            Visibility visibility,
            boolean allowOverwrite) {
        /** Creates a validated request. */
        public UploadObjectRequest {
            bucket = Checks.text(bucket, "bucket");
            key = Checks.text(key, "key");
            Uris.objectKey(key);
            source = Objects.requireNonNull(source, "source");
            originalFilename = Checks.text(originalFilename, "originalFilename");
            visibility = requestVisibility(visibility);
        }

        /** Creates a builder using private visibility and the source filename. */
        public static Builder builder(String bucket, String key, UploadSource source) {
            return new Builder(bucket, key, source);
        }

        /** Builder for {@link UploadObjectRequest}. */
        public static final class Builder {
            private final String bucket;
            private final String key;
            private final UploadSource source;
            private String originalFilename;
            private Visibility visibility = Visibility.PRIVATE;
            private boolean allowOverwrite;

            private Builder(String bucket, String key, UploadSource source) {
                this.bucket = bucket;
                this.key = key;
                this.source = Objects.requireNonNull(source, "source");
                this.originalFilename = source.filename();
            }

            /** Overrides the original filename metadata. */
            public Builder originalFilename(String originalFilename) {
                this.originalFilename = originalFilename;
                return this;
            }

            /** Sets public or private visibility. */
            public Builder visibility(Visibility visibility) {
                this.visibility = visibility;
                return this;
            }

            /** Allows replacing an object with the same key. */
            public Builder allowOverwrite(boolean allowOverwrite) {
                this.allowOverwrite = allowOverwrite;
                return this;
            }

            /** Builds the request. */
            public UploadObjectRequest build() {
                return new UploadObjectRequest(bucket, key, source, originalFilename, visibility, allowOverwrite);
            }
        }
    }

    /**
     * One item in an object or site batch upload.
     *
     * @param relativePath manifest-relative path
     * @param source repeatable upload source
     */
    public record UploadItem(String relativePath, UploadSource source) {
        /** Creates a validated item. */
        public UploadItem {
            relativePath = Checks.text(relativePath, "relativePath");
            Uris.objectKey(relativePath);
            source = Objects.requireNonNull(source, "source");
        }
    }

    /**
     * Request for a backend-atomic multipart object upload.
     *
     * @param bucket bucket name
     * @param prefix common object-key prefix
     * @param visibility requested visibility
     * @param allowOverwrite whether existing keys may be replaced
     * @param items upload items
     */
    public record UploadBatchRequest(
            String bucket,
            String prefix,
            Visibility visibility,
            boolean allowOverwrite,
            List<UploadItem> items) {
        /** Creates a validated request. */
        public UploadBatchRequest {
            bucket = Checks.text(bucket, "bucket");
            prefix = prefix == null ? "" : prefix.trim();
            visibility = requestVisibility(visibility);
            items = Checks.list(items, 1, 2000, "items");
            Set<String> paths = new HashSet<>();
            for (UploadItem item : items) {
                if (!paths.add(item.relativePath())) {
                    throw new LightOssValidationException("items contain a duplicate relativePath: " + item.relativePath());
                }
            }
        }

        /** Creates a builder using private visibility. */
        public static Builder builder(String bucket, List<UploadItem> items) {
            return new Builder(bucket, items);
        }

        /** Builder for {@link UploadBatchRequest}. */
        public static final class Builder {
            private final String bucket;
            private final List<UploadItem> items;
            private String prefix = "";
            private Visibility visibility = Visibility.PRIVATE;
            private boolean allowOverwrite;

            private Builder(String bucket, List<UploadItem> items) {
                this.bucket = bucket;
                this.items = items;
            }

            /** Prepends a common object-key prefix. */
            public Builder prefix(String prefix) {
                this.prefix = prefix;
                return this;
            }

            /** Sets public or private visibility. */
            public Builder visibility(Visibility visibility) {
                this.visibility = visibility;
                return this;
            }

            /** Allows replacing existing object keys. */
            public Builder allowOverwrite(boolean allowOverwrite) {
                this.allowOverwrite = allowOverwrite;
                return this;
            }

            /** Builds the request. */
            public UploadBatchRequest build() {
                return new UploadBatchRequest(bucket, prefix, visibility, allowOverwrite, items);
            }
        }
    }

    /**
     * Result of a backend-atomic batch upload.
     *
     * @param uploadedCount uploaded item count
     * @param items uploaded object metadata
     */
    public record BatchUploadResult(int uploadedCount, List<ObjectInfo> items) {
        /** Creates an immutable result. */
        public BatchUploadResult {
            items = List.copyOf(items);
        }
    }

    /**
     * Request for an object download.
     *
     * @param bucket bucket name
     * @param key object key
     * @param forceDownload whether to request attachment disposition
     */
    public record DownloadObjectRequest(String bucket, String key, boolean forceDownload) {
        /** Creates a validated request. */
        public DownloadObjectRequest {
            bucket = Checks.text(bucket, "bucket");
            key = Checks.text(key, "key");
            Uris.objectKey(key);
        }

        /** Creates a builder. */
        public static Builder builder(String bucket, String key) {
            return new Builder(bucket, key);
        }

        /** Builder for {@link DownloadObjectRequest}. */
        public static final class Builder {
            private final String bucket;
            private final String key;
            private boolean forceDownload;

            private Builder(String bucket, String key) {
                this.bucket = bucket;
                this.key = key;
            }

            /** Requests an attachment response. */
            public Builder forceDownload(boolean forceDownload) {
                this.forceDownload = forceDownload;
                return this;
            }

            /** Builds the request. */
            public DownloadObjectRequest build() {
                return new DownloadObjectRequest(bucket, key, forceDownload);
            }
        }
    }
}
