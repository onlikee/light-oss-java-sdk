package com.onlikee.lightoss;

import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.MultipartBody;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.transfer.ContentMetadata;
import com.onlikee.lightoss.transfer.DownloadResponse;
import com.onlikee.lightoss.transfer.UploadSource;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Static-site management, publication, and public content operations. */
public final class SiteClient {
    private static final String API_PATH = "/api/v1/sites";
    private final ClientContext context;

    SiteClient(ClientContext context) {
        this.context = context;
    }

    /** Creates a site configuration. */
    public LightOssResponse<Site> create(SiteRequest request) {
        Objects.requireNonNull(request, "request");
        return siteJson("POST", Uris.endpoint(context.baseUri(), API_PATH), request, 201);
    }

    /** Lists all site configurations. */
    public LightOssResponse<List<Site>> list() {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), API_PATH),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.list(context.json(), data, "items", requestId, this::parseSite));
    }

    /** Gets one site configuration. */
    public LightOssResponse<Site> get(long siteId) {
        return context.json(
                "GET", siteApiUri(siteId), ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(), null, Map.of(), 200, this::parseSite);
    }

    /** Replaces one site configuration. */
    public LightOssResponse<Site> update(long siteId, SiteRequest request) {
        Objects.requireNonNull(request, "request");
        return siteJson("PUT", siteApiUri(siteId), request, 200);
    }

    /** Deletes one site configuration. */
    public LightOssResponse<Void> delete(long siteId) {
        return context.noContent(
                "DELETE", siteApiUri(siteId), ClientContext.AuthMode.REQUIRED, Map.of(), 204);
    }

    /** Uploads and publishes 1 to 2000 files in one backend-atomic operation. */
    public LightOssResponse<PublishResult> publish(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        MultipartBody multipart = new MultipartBody()
                .text("bucket", request.bucket())
                .text("parent_prefix", request.parentPrefix())
                .text("domains", jsonText(request.domains()), "application/json; charset=utf-8")
                .text("enabled", Boolean.toString(request.enabled()))
                .text("index_document", request.indexDocument())
                .text("error_document", request.errorDocument())
                .text("spa_fallback", Boolean.toString(request.spaFallback()));
        List<Map<String, String>> manifest = new ArrayList<>(request.items().size());
        for (int index = 0; index < request.items().size(); index++) {
            ObjectClient.UploadItem item = request.items().get(index);
            String field = "file_" + index;
            manifest.add(Map.of("file_field", field, "relative_path", item.relativePath()));
            multipart.file(field, item.source());
        }
        multipart.text("manifest", jsonText(manifest), "application/json; charset=utf-8");
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), API_PATH + "/publish"),
                ClientContext.AuthMode.REQUIRED,
                multipart.publisher(),
                multipart.contentType(),
                Map.of(),
                201,
                (data, requestId) -> new PublishResult(
                        context.json().requiredInt(data, "uploaded_count", requestId),
                        parseSite(context.json().requiredObject(data, "site", requestId), requestId)));
    }

    /** Uploads one file and publishes it as a site. */
    public LightOssResponse<Site> publishFile(PublishFileRequest request) {
        Objects.requireNonNull(request, "request");
        MultipartBody multipart = new MultipartBody()
                .text("bucket", request.bucket())
                .text("parent_prefix", request.parentPrefix())
                .text("domains", jsonText(request.domains()), "application/json; charset=utf-8")
                .text("enabled", Boolean.toString(request.enabled()))
                .text("error_document", request.errorDocument())
                .text("spa_fallback", Boolean.toString(request.spaFallback()))
                .file("file", request.source());
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), API_PATH + "/publish/file"),
                ClientContext.AuthMode.REQUIRED,
                multipart.publisher(),
                multipart.contentType(),
                Map.of(),
                201,
                this::parseSite);
    }

    /** Publishes an existing object as a site. */
    public LightOssResponse<Site> publishObject(PublishObjectRequest request) {
        Objects.requireNonNull(request, "request");
        Object body = context.json().object(
                "bucket", request.bucket(),
                "object_key", request.objectKey(),
                "enabled", request.enabled(),
                "error_document", request.errorDocument(),
                "spa_fallback", request.spaFallback(),
                "domains", request.domains());
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), API_PATH + "/publish/object"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(body),
                "application/json",
                Map.of(),
                201,
                this::parseSite);
    }

    /** Streams public site content by site ID without attaching a bearer token. */
    public DownloadResponse downloadPublished(long siteId, String path) {
        return context.stream(
                "GET", publicSiteUri(siteId, path), ClientContext.AuthMode.NONE, Map.of(), 200);
    }

    /** Reads public site metadata by site ID without attaching a bearer token. */
    public LightOssResponse<ContentMetadata> headPublished(long siteId, String path) {
        return context.head(publicSiteUri(siteId, path), ClientContext.AuthMode.NONE, Map.of(), 200);
    }

    /** Streams a public custom-domain URI without attaching a bearer token. */
    public DownloadResponse downloadDomain(URI uri) {
        return context.stream(
                "GET", Uris.publicSiteUri(uri), ClientContext.AuthMode.NONE, Map.of(), 200);
    }

    /** Reads metadata from a public custom-domain URI without attaching a bearer token. */
    public LightOssResponse<ContentMetadata> headDomain(URI uri) {
        return context.head(Uris.publicSiteUri(uri), ClientContext.AuthMode.NONE, Map.of(), 200);
    }

    private LightOssResponse<Site> siteJson(String method, URI uri, SiteRequest request, int status) {
        return context.json(
                method,
                uri,
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(siteRequestBody(request)),
                "application/json",
                Map.of(),
                status,
                this::parseSite);
    }

    private Object siteRequestBody(SiteRequest request) {
        return context.json().object(
                "bucket", request.bucket(),
                "root_prefix", request.rootPrefix(),
                "enabled", request.enabled(),
                "index_document", request.indexDocument(),
                "error_document", request.errorDocument(),
                "spa_fallback", request.spaFallback(),
                "domains", request.domains());
    }

    private Site parseSite(JsonNode node, String requestId) {
        return new Site(
                context.json().requiredLong(node, "id", requestId),
                context.json().requiredText(node, "bucket", requestId),
                context.json().requiredText(node, "root_prefix", requestId),
                context.json().requiredBoolean(node, "enabled", requestId),
                context.json().requiredText(node, "index_document", requestId),
                context.json().requiredText(node, "error_document", requestId),
                context.json().requiredBoolean(node, "spa_fallback", requestId),
                context.json().requiredStrings(node, "domains", requestId),
                context.json().requiredInstant(node, "created_at", requestId),
                context.json().requiredInstant(node, "updated_at", requestId));
    }

    private URI siteApiUri(long siteId) {
        return Uris.endpoint(context.baseUri(), API_PATH + "/" + Checks.positive(siteId, "siteId"));
    }

    private URI publicSiteUri(long siteId, String path) {
        String suffix = "/sites/" + Checks.positive(siteId, "siteId");
        if (path != null && !path.isBlank()) {
            String encodedPath = Uris.sitePath(path);
            if (!encodedPath.isEmpty()) {
                suffix += "/" + encodedPath;
            }
        }
        return Uris.endpoint(context.baseUri(), suffix);
    }

    private String jsonText(Object value) {
        return new String(context.json().write(value), StandardCharsets.UTF_8);
    }

    private static List<String> domains(List<String> domains, boolean required) {
        Objects.requireNonNull(domains, "domains");
        List<String> copy = domains.stream().map(domain -> Checks.rawText(domain, "domain")).toList();
        if (required && copy.isEmpty()) {
            throw new LightOssValidationException("domains must contain at least one item");
        }
        return List.copyOf(copy);
    }

    /**
     * Site metadata.
     *
     * @param id site identifier
     * @param bucket bucket name
     * @param rootPrefix bucket-relative root prefix
     * @param enabled whether public serving is enabled
     * @param indexDocument index document
     * @param errorDocument optional error document
     * @param spaFallback whether SPA fallback is enabled
     * @param domains bound custom domains
     * @param createdAt creation time
     * @param updatedAt last update time
     */
    public record Site(
            long id,
            String bucket,
            String rootPrefix,
            boolean enabled,
            String indexDocument,
            String errorDocument,
            boolean spaFallback,
            List<String> domains,
            Instant createdAt,
            Instant updatedAt) {
        /** Creates immutable site metadata. */
        public Site {
            bucket = Objects.requireNonNull(bucket, "bucket");
            rootPrefix = Objects.requireNonNull(rootPrefix, "rootPrefix");
            indexDocument = Objects.requireNonNull(indexDocument, "indexDocument");
            errorDocument = Objects.requireNonNull(errorDocument, "errorDocument");
            domains = List.copyOf(domains);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * Request used for both site creation and replacement.
     *
     * @param bucket bucket name
     * @param rootPrefix bucket-relative root prefix
     * @param enabled whether public serving is enabled
     * @param indexDocument index document
     * @param errorDocument optional error document
     * @param spaFallback whether SPA fallback is enabled
     * @param domains custom domains
     */
    public record SiteRequest(
            String bucket,
            String rootPrefix,
            boolean enabled,
            String indexDocument,
            String errorDocument,
            boolean spaFallback,
            List<String> domains) {
        /** Creates a validated request. */
        public SiteRequest {
            bucket = Checks.rawText(bucket, "bucket");
            rootPrefix = rootPrefix == null ? "" : rootPrefix;
            indexDocument = indexDocument == null ? "" : indexDocument;
            errorDocument = errorDocument == null ? "" : errorDocument;
            domains = SiteClient.domains(domains, false);
        }

        /** Creates a builder with normal site defaults. */
        public static Builder builder(String bucket) { return new Builder(bucket); }

        /** Builder for {@link SiteRequest}. */
        public static final class Builder {
            private final String bucket;
            private String rootPrefix = "";
            private boolean enabled = true;
            private String indexDocument = "index.html";
            private String errorDocument = "";
            private boolean spaFallback;
            private List<String> domains = List.of();

            private Builder(String bucket) { this.bucket = bucket; }

            /** Sets the bucket-relative root prefix. */
            public Builder rootPrefix(String value) { rootPrefix = value; return this; }
            /** Enables or disables public serving. */
            public Builder enabled(boolean value) { enabled = value; return this; }
            /** Sets the index document; an empty value uses the backend default. */
            public Builder indexDocument(String value) { indexDocument = value; return this; }
            /** Sets the optional error document. */
            public Builder errorDocument(String value) { errorDocument = value; return this; }
            /** Enables or disables SPA fallback. */
            public Builder spaFallback(boolean value) { spaFallback = value; return this; }
            /** Sets custom domains. */
            public Builder domains(List<String> value) { domains = value; return this; }
            /** Builds the request. */
            public SiteRequest build() {
                return new SiteRequest(bucket, rootPrefix, enabled, indexDocument, errorDocument, spaFallback, domains);
            }
        }
    }

    /**
     * Request for an atomic folder upload and site publication.
     *
     * @param bucket bucket name
     * @param parentPrefix optional parent prefix
     * @param enabled whether public serving is enabled
     * @param indexDocument index document
     * @param errorDocument optional error document
     * @param spaFallback whether SPA fallback is enabled
     * @param domains required custom domains
     * @param items uploaded files
     */
    public record PublishRequest(
            String bucket,
            String parentPrefix,
            boolean enabled,
            String indexDocument,
            String errorDocument,
            boolean spaFallback,
            List<String> domains,
            List<ObjectClient.UploadItem> items) {
        /** Creates a validated request. */
        public PublishRequest {
            bucket = Checks.rawText(bucket, "bucket");
            parentPrefix = parentPrefix == null ? "" : parentPrefix;
            indexDocument = indexDocument == null ? "" : indexDocument;
            errorDocument = errorDocument == null ? "" : errorDocument;
            domains = SiteClient.domains(domains, true);
            items = Checks.list(items, 1, 2000, "items");
            Set<String> paths = new HashSet<>();
            for (ObjectClient.UploadItem item : items) {
                if (!paths.add(item.relativePath())) {
                    throw new LightOssValidationException("items contain a duplicate relativePath: " + item.relativePath());
                }
            }
        }

        /** Creates a builder with publication defaults. */
        public static Builder builder(String bucket, List<String> domains, List<ObjectClient.UploadItem> items) {
            return new Builder(bucket, domains, items);
        }

        /** Builder for {@link PublishRequest}. */
        public static final class Builder {
            private final String bucket;
            private final List<String> domains;
            private final List<ObjectClient.UploadItem> items;
            private String parentPrefix = "";
            private boolean enabled = true;
            private String indexDocument = "index.html";
            private String errorDocument = "";
            private boolean spaFallback = true;

            private Builder(String bucket, List<String> domains, List<ObjectClient.UploadItem> items) {
                this.bucket = bucket; this.domains = domains; this.items = items;
            }

            /** Sets the optional parent prefix. */
            public Builder parentPrefix(String v) { parentPrefix = v; return this; }
            /** Enables or disables public serving. */
            public Builder enabled(boolean v) { enabled = v; return this; }
            /** Sets the index document within the uploaded root; an empty value uses the backend default. */
            public Builder indexDocument(String v) { indexDocument = v; return this; }
            /** Sets the optional error document. */
            public Builder errorDocument(String v) { errorDocument = v; return this; }
            /** Enables or disables SPA fallback. */
            public Builder spaFallback(boolean v) { spaFallback = v; return this; }
            /** Builds the request. */
            public PublishRequest build() {
                return new PublishRequest(bucket, parentPrefix, enabled, indexDocument, errorDocument,
                        spaFallback, domains, items);
            }
        }
    }

    /**
     * Result of publishing an uploaded folder.
     *
     * @param uploadedCount uploaded item count
     * @param site created site metadata
     */
    public record PublishResult(int uploadedCount, Site site) {
        /** Creates a publication result. */
        public PublishResult { site = Objects.requireNonNull(site, "site"); }
    }

    /**
     * Request for uploading and publishing a single file.
     *
     * @param bucket bucket name
     * @param parentPrefix optional parent prefix
     * @param enabled whether public serving is enabled
     * @param errorDocument optional error document
     * @param spaFallback whether SPA fallback is enabled
     * @param domains required custom domains
     * @param source repeatable upload source
     */
    public record PublishFileRequest(
            String bucket,
            String parentPrefix,
            boolean enabled,
            String errorDocument,
            boolean spaFallback,
            List<String> domains,
            UploadSource source) {
        /** Creates a validated request. */
        public PublishFileRequest {
            bucket = Checks.rawText(bucket, "bucket");
            parentPrefix = parentPrefix == null ? "" : parentPrefix;
            errorDocument = errorDocument == null ? "" : errorDocument;
            domains = SiteClient.domains(domains, true);
            source = Objects.requireNonNull(source, "source");
        }

        /** Creates a builder with publication defaults. */
        public static Builder builder(String bucket, List<String> domains, UploadSource source) {
            return new Builder(bucket, domains, source);
        }

        /** Builder for {@link PublishFileRequest}. */
        public static final class Builder {
            private final String bucket; private final List<String> domains; private final UploadSource source;
            private String parentPrefix = ""; private boolean enabled = true;
            private String errorDocument = ""; private boolean spaFallback = true;
            private Builder(String bucket, List<String> domains, UploadSource source) {
                this.bucket = bucket; this.domains = domains; this.source = source;
            }
            /** Sets the optional parent prefix. */
            public Builder parentPrefix(String v) { parentPrefix = v; return this; }
            /** Enables or disables public serving. */
            public Builder enabled(boolean v) { enabled = v; return this; }
            /** Sets the optional error document. */
            public Builder errorDocument(String v) { errorDocument = v; return this; }
            /** Enables or disables SPA fallback. */
            public Builder spaFallback(boolean v) { spaFallback = v; return this; }
            /** Builds the request. */
            public PublishFileRequest build() {
                return new PublishFileRequest(bucket, parentPrefix, enabled, errorDocument, spaFallback, domains, source);
            }
        }
    }

    /**
     * Request for publishing an existing object as a site.
     *
     * @param bucket bucket name
     * @param objectKey source object key
     * @param enabled whether public serving is enabled
     * @param errorDocument optional error document
     * @param spaFallback whether SPA fallback is enabled
     * @param domains required custom domains
     */
    public record PublishObjectRequest(
            String bucket,
            String objectKey,
            boolean enabled,
            String errorDocument,
            boolean spaFallback,
            List<String> domains) {
        /** Creates a validated request. */
        public PublishObjectRequest {
            bucket = Checks.rawText(bucket, "bucket");
            objectKey = Checks.rawText(objectKey, "objectKey");
            Uris.objectKey(objectKey);
            errorDocument = errorDocument == null ? "" : errorDocument;
            domains = SiteClient.domains(domains, true);
        }

        /** Creates a builder with publication defaults. */
        public static Builder builder(String bucket, String objectKey, List<String> domains) {
            return new Builder(bucket, objectKey, domains);
        }

        /** Builder for {@link PublishObjectRequest}. */
        public static final class Builder {
            private final String bucket; private final String objectKey; private final List<String> domains;
            private boolean enabled = true; private String errorDocument = ""; private boolean spaFallback = true;
            private Builder(String bucket, String objectKey, List<String> domains) {
                this.bucket = bucket; this.objectKey = objectKey; this.domains = domains;
            }
            /** Enables or disables public serving. */
            public Builder enabled(boolean v) { enabled = v; return this; }
            /** Sets the optional error document. */
            public Builder errorDocument(String v) { errorDocument = v; return this; }
            /** Enables or disables SPA fallback. */
            public Builder spaFallback(boolean v) { spaFallback = v; return this; }
            /** Builds the request. */
            public PublishObjectRequest build() {
                return new PublishObjectRequest(bucket, objectKey, enabled, errorDocument, spaFallback, domains);
            }
        }
    }
}
