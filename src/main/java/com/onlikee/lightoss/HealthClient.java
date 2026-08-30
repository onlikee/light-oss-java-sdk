package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Uris;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Health and readiness operations. */
public final class HealthClient {
    private final ClientContext context;

    HealthClient(ClientContext context) {
        this.context = context;
    }

    /** Returns process liveness without authentication. */
    public LightOssResponse<Liveness> liveness() {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), "/livez"),
                ClientContext.AuthMode.NONE,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> new Liveness(
                        context.json().requiredText(data, "status", requestId),
                        context.json().requiredText(data, "version", requestId)));
    }

    /** Returns dependency readiness without authentication. */
    public LightOssResponse<Readiness> readiness() {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), "/readyz"),
                ClientContext.AuthMode.NONE,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                (data, requestId) -> new Readiness(
                        context.json().requiredText(data, "status", requestId),
                        context.json().requiredText(data, "version", requestId)));
    }

    /**
     * Returns authenticated service and database health.
     *
     * <p>HTTP 503 is parsed as health data because the backend returns the same health envelope
     * with {@code db=error} when its database is unavailable.</p>
     */
    public LightOssResponse<Health> health() {
        return context.jsonStatuses(
                "GET",
                Uris.endpoint(context.baseUri(), "/api/v1/healthz"),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                Set.of(200, 503),
                (data, requestId) -> {
                    var status = context.json().requiredObject(data, "status", requestId);
                    return new Health(
                            context.json().requiredText(status, "service", requestId),
                            context.json().requiredText(status, "db", requestId),
                            context.json().requiredText(data, "version", requestId));
                });
    }

    /**
     * Liveness result.
     *
     * @param status process status
     * @param version service version
     */
    public record Liveness(String status, String version) {
        /** Creates a liveness result. */
        public Liveness {
            status = Objects.requireNonNull(status, "status");
            version = Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Readiness result.
     *
     * @param status readiness status
     * @param version service version
     */
    public record Readiness(String status, String version) {
        /** Creates a readiness result. */
        public Readiness {
            status = Objects.requireNonNull(status, "status");
            version = Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Authenticated health result.
     *
     * @param service service status
     * @param database database status
     * @param version service version
     */
    public record Health(String service, String database, String version) {
        /** Creates a health result. */
        public Health {
            service = Objects.requireNonNull(service, "service");
            database = Objects.requireNonNull(database, "database");
            version = Objects.requireNonNull(version, "version");
        }

        /** Returns whether both service and database report healthy. */
        public boolean healthy() {
            return service.equals("ok") && database.equals("ok");
        }
    }
}
