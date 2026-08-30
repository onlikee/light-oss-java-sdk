package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.Uris;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bucket management operations. */
public final class BucketClient {
    private final ClientContext context;

    BucketClient(ClientContext context) {
        this.context = context;
    }

    /** Creates a bucket. */
    public LightOssResponse<Bucket> create(String name) {
        String checkedName = Checks.text(name, "name");
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), "/api/v1/buckets"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("name", checkedName)),
                "application/json",
                Map.of(),
                201,
                this::parseBucket);
    }

    /** Lists all buckets. */
    public LightOssResponse<List<Bucket>> list() {
        return list("");
    }

    /** Lists buckets matching an optional search string. */
    public LightOssResponse<List<Bucket>> list(String search) {
        Map<String, Object> query = Uris.query();
        query.put("search", search == null ? "" : search.trim());
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), "/api/v1/buckets", query),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> Parsers.list(context.json(), data, "items", requestId, this::parseBucket));
    }

    /** Deletes a bucket and its backend-managed contents. */
    public LightOssResponse<Void> delete(String bucket) {
        return context.noContent(
                "DELETE",
                Uris.endpoint(context.baseUri(), "/api/v1/buckets/" + Uris.segment(bucket)),
                ClientContext.AuthMode.REQUIRED,
                Map.of(),
                204);
    }

    private Bucket parseBucket(tools.jackson.databind.JsonNode node, String requestId) {
        return new Bucket(
                context.json().requiredLong(node, "id", requestId),
                context.json().requiredText(node, "name", requestId),
                context.json().requiredInstant(node, "created_at", requestId),
                context.json().requiredInstant(node, "updated_at", requestId));
    }

    /**
     * Bucket metadata.
     *
     * @param id bucket identifier
     * @param name bucket name
     * @param createdAt creation time
     * @param updatedAt last update time
     */
    public record Bucket(long id, String name, Instant createdAt, Instant updatedAt) {
        /** Creates immutable bucket metadata. */
        public Bucket {
            name = Objects.requireNonNull(name, "name");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }
}
