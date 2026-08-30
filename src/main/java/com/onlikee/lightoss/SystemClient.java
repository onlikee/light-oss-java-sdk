package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Parsers;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.RateLimitBackend;
import com.onlikee.lightoss.model.StorageLimitStatus;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** System statistics, runtime metrics, and quota operations. */
public final class SystemClient {
    private final ClientContext context;

    SystemClient(ClientContext context) {
        this.context = context;
    }

    /** Returns runtime counters and limiter metrics. */
    public LightOssResponse<SystemMetrics> metrics() {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), "/api/v1/system/metrics"),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                this::parseMetrics);
    }

    /** Returns host and storage statistics. */
    public LightOssResponse<SystemStats> stats() {
        return context.json(
                "GET",
                Uris.endpoint(context.baseUri(), "/api/v1/system/stats"),
                ClientContext.AuthMode.REQUIRED,
                HttpRequest.BodyPublishers.noBody(),
                null,
                Map.of(),
                200,
                this::parseStats);
    }

    /** Updates the logical storage quota in bytes. */
    public LightOssResponse<Storage> updateStorageQuota(long maxBytes) {
        Checks.positive(maxBytes, "maxBytes");
        return context.json(
                "PUT",
                Uris.endpoint(context.baseUri(), "/api/v1/system/storage/quota"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(context.json().object("max_bytes", maxBytes)),
                "application/json",
                Map.of(),
                200,
                this::parseStorage);
    }

    private SystemMetrics parseMetrics(JsonNode data, String requestId) {
        JsonNode uploads = context.json().requiredObject(data, "uploads", requestId);
        JsonNode transactions = context.json().requiredObject(data, "transactions", requestId);
        JsonNode cleanup = context.json().requiredObject(data, "cleanup", requestId);
        JsonNode quota = context.json().requiredObject(data, "quota", requestId);
        JsonNode database = context.json().requiredObject(data, "database", requestId);
        JsonNode rateLimit = context.json().requiredObject(data, "rate_limit", requestId);
        return new SystemMetrics(
                new UploadMetrics(
                        context.json().requiredLong(uploads, "staged", requestId),
                        context.json().requiredLong(uploads, "failed", requestId),
                        context.json().requiredLong(uploads, "bytes", requestId),
                        context.json().requiredLong(uploads, "staging_duration_ns", requestId),
                        context.json().requiredLong(uploads, "reservation_failures", requestId)),
                new TransactionMetrics(
                        context.json().requiredLong(transactions, "completed", requestId),
                        context.json().requiredLong(transactions, "failed", requestId),
                        context.json().requiredLong(transactions, "duration_ns", requestId)),
                new CleanupMetrics(
                        context.json().requiredLong(cleanup, "completed", requestId),
                        context.json().requiredLong(cleanup, "failed", requestId),
                        context.json().requiredLong(cleanup, "backlog", requestId)),
                new QuotaMetrics(
                        context.json().requiredLong(quota, "used_bytes", requestId),
                        context.json().requiredLong(quota, "reserved_bytes", requestId),
                        context.json().requiredLong(quota, "max_bytes", requestId),
                        context.json().requiredLong(quota, "remaining_bytes", requestId)),
                new DatabaseMetrics(
                        context.json().requiredInt(database, "max_open_connections", requestId),
                        context.json().requiredInt(database, "open_connections", requestId),
                        context.json().requiredInt(database, "in_use", requestId),
                        context.json().requiredInt(database, "idle", requestId),
                        context.json().requiredLong(database, "wait_count", requestId),
                        context.json().requiredLong(database, "wait_duration_ns", requestId),
                        context.json().requiredLong(database, "max_idle_closed", requestId),
                        context.json().requiredLong(database, "max_idle_time_closed", requestId),
                        context.json().requiredLong(database, "max_lifetime_closed", requestId)),
                new RateLimits(
                        parseLimiter(rateLimit, "ip", requestId),
                        parseLimiter(rateLimit, "public", requestId),
                        parseLimiter(rateLimit, "management", requestId),
                        parseLimiter(rateLimit, "upload", requestId),
                        parseLimiter(rateLimit, "sign", requestId),
                        parseLimiter(rateLimit, "health", requestId)));
    }

    private RateLimiterMetrics parseLimiter(JsonNode rateLimit, String field, String requestId) {
        JsonNode item = context.json().requiredObject(rateLimit, field, requestId);
        return new RateLimiterMetrics(
                new RateLimitBackend(context.json().requiredText(item, "backend", requestId)),
                context.json().requiredInt(item, "entries", requestId),
                context.json().requiredInt(item, "max_entries", requestId),
                context.json().requiredLong(item, "expired_evictions", requestId),
                context.json().requiredLong(item, "capacity_evictions", requestId),
                context.json().requiredLong(item, "capacity_rejections", requestId),
                context.json().requiredLong(item, "rejected_requests", requestId),
                context.json().requiredLong(item, "store_errors", requestId));
    }

    private SystemStats parseStats(JsonNode data, String requestId) {
        JsonNode cpu = context.json().requiredObject(data, "cpu", requestId);
        JsonNode memory = context.json().requiredObject(data, "memory", requestId);
        return new SystemStats(
                context.json().requiredText(data, "os", requestId),
                new Cpu(context.json().requiredDouble(cpu, "used_percent", requestId)),
                new Memory(
                        context.json().requiredLong(memory, "total_bytes", requestId),
                        context.json().requiredLong(memory, "used_bytes", requestId),
                        context.json().requiredLong(memory, "available_bytes", requestId),
                        context.json().requiredDouble(memory, "used_percent", requestId)),
                Parsers.list(context.json(), data, "disks", requestId, this::parseDisk),
                parseStorage(context.json().requiredObject(data, "storage", requestId), requestId));
    }

    private Disk parseDisk(JsonNode data, String requestId) {
        return new Disk(
                context.json().requiredText(data, "label", requestId),
                context.json().requiredText(data, "mount_point", requestId),
                context.json().requiredText(data, "filesystem", requestId),
                context.json().requiredLong(data, "total_bytes", requestId),
                context.json().requiredLong(data, "used_bytes", requestId),
                context.json().requiredLong(data, "free_bytes", requestId),
                context.json().requiredDouble(data, "used_percent", requestId),
                context.json().requiredBoolean(data, "contains_storage_root", requestId));
    }

    private Storage parseStorage(JsonNode data, String requestId) {
        return new Storage(
                context.json().requiredText(data, "root_path", requestId),
                context.json().requiredLong(data, "used_bytes", requestId),
                context.json().requiredLong(data, "max_bytes", requestId),
                context.json().requiredLong(data, "remaining_bytes", requestId),
                context.json().requiredDouble(data, "used_percent", requestId),
                new StorageLimitStatus(context.json().requiredText(data, "limit_status", requestId)));
    }

    /**
     * Complete runtime metrics snapshot.
     *
     * @param uploads upload metrics
     * @param transactions transaction metrics
     * @param cleanup cleanup-worker metrics
     * @param quota quota metrics
     * @param database database pool metrics
     * @param rateLimits rate-limit metrics
     */
    public record SystemMetrics(
            UploadMetrics uploads,
            TransactionMetrics transactions,
            CleanupMetrics cleanup,
            QuotaMetrics quota,
            DatabaseMetrics database,
            RateLimits rateLimits) {
    }

    /**
     * Upload metrics.
     *
     * @param staged staged upload count
     * @param failed failed upload count
     * @param bytes staged byte count
     * @param stagingDurationNanos cumulative staging duration
     * @param reservationFailures quota reservation failure count
     */
    public record UploadMetrics(long staged, long failed, long bytes, long stagingDurationNanos, long reservationFailures) {
    }

    /**
     * Transaction metrics.
     *
     * @param completed completed transaction count
     * @param failed failed transaction count
     * @param durationNanos cumulative duration
     */
    public record TransactionMetrics(long completed, long failed, long durationNanos) {
    }

    /**
     * Cleanup-worker metrics.
     *
     * @param completed completed cleanup count
     * @param failed failed cleanup count
     * @param backlog pending cleanup count
     */
    public record CleanupMetrics(long completed, long failed, long backlog) {
    }

    /**
     * Storage quota counters.
     *
     * @param usedBytes used bytes
     * @param reservedBytes reserved bytes
     * @param maxBytes configured maximum bytes
     * @param remainingBytes remaining bytes
     */
    public record QuotaMetrics(long usedBytes, long reservedBytes, long maxBytes, long remainingBytes) {
    }

    /**
     * Database pool metrics.
     *
     * @param maxOpenConnections maximum open connections
     * @param openConnections open connections
     * @param inUse connections in use
     * @param idle idle connections
     * @param waitCount wait count
     * @param waitDurationNanos cumulative wait duration
     * @param maxIdleClosed connections closed by idle limit
     * @param maxIdleTimeClosed connections closed by idle-time limit
     * @param maxLifetimeClosed connections closed by lifetime limit
     */
    public record DatabaseMetrics(
            int maxOpenConnections,
            int openConnections,
            int inUse,
            int idle,
            long waitCount,
            long waitDurationNanos,
            long maxIdleClosed,
            long maxIdleTimeClosed,
            long maxLifetimeClosed) {
    }

    /**
     * Metrics for all rate-limit namespaces.
     *
     * @param ip IP limiter metrics
     * @param publicRequests public-route limiter metrics
     * @param management management limiter metrics
     * @param upload upload limiter metrics
     * @param signing signing limiter metrics
     * @param health health limiter metrics
     */
    public record RateLimits(
            RateLimiterMetrics ip,
            RateLimiterMetrics publicRequests,
            RateLimiterMetrics management,
            RateLimiterMetrics upload,
            RateLimiterMetrics signing,
            RateLimiterMetrics health) {
    }

    /**
     * One rate-limit namespace snapshot.
     *
     * @param backend limiter backend
     * @param entries current entry count
     * @param maxEntries maximum entry count
     * @param expiredEvictions expired entry evictions
     * @param capacityEvictions capacity evictions
     * @param capacityRejections capacity rejections
     * @param rejectedRequests rejected request count
     * @param storeErrors backend store error count
     */
    public record RateLimiterMetrics(
            RateLimitBackend backend,
            int entries,
            int maxEntries,
            long expiredEvictions,
            long capacityEvictions,
            long capacityRejections,
            long rejectedRequests,
            long storeErrors) {
    }

    /**
     * Host and storage statistics.
     *
     * @param operatingSystem operating-system name
     * @param cpu CPU usage
     * @param memory memory usage
     * @param disks filesystem usage entries
     * @param storage Light OSS storage usage
     */
    public record SystemStats(String operatingSystem, Cpu cpu, Memory memory, List<Disk> disks, Storage storage) {
        /** Creates immutable host and storage statistics. */
        public SystemStats {
            operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
            cpu = Objects.requireNonNull(cpu, "cpu");
            memory = Objects.requireNonNull(memory, "memory");
            disks = List.copyOf(disks);
            storage = Objects.requireNonNull(storage, "storage");
        }
    }

    /**
     * CPU usage.
     *
     * @param usedPercent used percentage
     */
    public record Cpu(double usedPercent) {
    }

    /**
     * Memory usage.
     *
     * @param totalBytes total bytes
     * @param usedBytes used bytes
     * @param availableBytes available bytes
     * @param usedPercent used percentage
     */
    public record Memory(long totalBytes, long usedBytes, long availableBytes, double usedPercent) {
    }

    /**
     * Filesystem usage.
     *
     * @param label filesystem label
     * @param mountPoint mount point
     * @param filesystem filesystem type
     * @param totalBytes total bytes
     * @param usedBytes used bytes
     * @param freeBytes free bytes
     * @param usedPercent used percentage
     * @param containsStorageRoot whether this filesystem contains the storage root
     */
    public record Disk(
            String label,
            String mountPoint,
            String filesystem,
            long totalBytes,
            long usedBytes,
            long freeBytes,
            double usedPercent,
            boolean containsStorageRoot) {
    }

    /**
     * Logical Light OSS storage quota and usage.
     *
     * @param rootPath storage root path
     * @param usedBytes used bytes
     * @param maxBytes configured maximum bytes
     * @param remainingBytes remaining bytes
     * @param usedPercent used percentage
     * @param limitStatus forward-compatible limit status
     */
    public record Storage(
            String rootPath,
            long usedBytes,
            long maxBytes,
            long remainingBytes,
            double usedPercent,
            StorageLimitStatus limitStatus) {
    }
}
