package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onlikee.lightoss.model.EntryType;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.DownloadResponse;
import com.onlikee.lightoss.transfer.UploadSource;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RealBackendIntegrationTest {
    private static final String BASE_URI_ENV = "LIGHT_OSS_INTEGRATION_BASE_URI";
    private static final String TOKEN_ENV = "LIGHT_OSS_INTEGRATION_TOKEN";

    @Test
    void exercisesEveryOperationAgainstRealBackendAndCleansUp() throws Exception {
        String baseUriValue = System.getenv(BASE_URI_ENV);
        String token = System.getenv(TOKEN_ENV);
        Assumptions.assumeTrue(baseUriValue != null && !baseUriValue.isBlank(),
                () -> BASE_URI_ENV + " is not configured");
        Assumptions.assumeTrue(token != null && !token.isBlank(),
                () -> TOKEN_ENV + " is not configured");

        URI baseUri = URI.create(baseUriValue);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toLowerCase(Locale.ROOT);
        String bucket = "sdk-e2e-" + suffix;
        String manualDomain = "manual-" + suffix + ".sdk.test";
        List<Long> siteIds = new ArrayList<>();
        boolean bucketCreated = false;

        try (LightOssClient client = LightOssClient.builder(baseUri)
                .bearerToken(token)
                .requestIdProvider(new PacedRequestIds(Duration.ofMillis(225)))
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            assertEquals("ok", response(client.health().liveness()).status());
            assertEquals("ready", response(client.health().readiness()).status());
            assertTrue(response(client.health().health()).healthy());

            SystemClient.SystemMetrics metrics = response(client.system().metrics());
            assertTrue(metrics.database().maxOpenConnections() > 0);
            SystemClient.SystemStats stats = response(client.system().stats());
            long originalQuota = stats.storage().maxBytes();
            assertTrue(originalQuota > 0);
            assertEquals(originalQuota, response(client.system().updateStorageQuota(originalQuota)).maxBytes());

            assertEquals(bucket, response(client.buckets().create(bucket)).name());
            bucketCreated = true;
            assertTrue(response(client.buckets().list(bucket)).stream()
                    .anyMatch(item -> item.name().equals(bucket)));

            ExplorerClient.FolderNode emptyFolder = response(client.explorer().createFolder(
                    new ExplorerClient.CreateFolderRequest(bucket, "", "empty")));
            assertTrue(response(client.explorer().listFolders(bucket)).stream()
                    .anyMatch(folder -> folder.path().equals(emptyFolder.path())));
            response(client.explorer().deleteFolder(
                    new ExplorerClient.DeleteFolderRequest(bucket, emptyFolder.path(), false)));

            byte[] privateContent = "private-object-content".getBytes(StandardCharsets.UTF_8);
            ObjectClient.UploadObjectRequest singleUpload = ObjectClient.UploadObjectRequest
                    .builder(bucket, "single.txt", bytes("original-中文.txt", "text/plain", privateContent))
                    .build();
            assertEquals("single.txt", response(client.objects().upload(singleUpload)).objectKey());

            ObjectClient.BatchUploadResult batchUpload = response(client.objects().uploadBatch(
                    ObjectClient.UploadBatchRequest.builder(bucket, List.of(
                                    item("a.txt", "alpha"),
                                    item("sub/b.txt", "beta")))
                            .prefix("tree/")
                            .build()));
            assertEquals(2, batchUpload.uploadedCount());

            response(client.objects().upload(ObjectClient.UploadObjectRequest
                    .builder(bucket, "batch-delete.txt", bytes("batch-delete.txt", "text/plain", "delete-me"))
                    .build()));
            assertFalse(response(client.objects().list(ObjectClient.ListObjectsRequest.builder(bucket)
                            .prefix("tree/")
                            .limit(1)
                            .build()))
                    .items().isEmpty());

            try (DownloadResponse download = client.objects().download(
                    ObjectClient.DownloadObjectRequest.builder(bucket, "single.txt")
                            .forceDownload(true)
                            .build())) {
                assertEquals("private-object-content", utf8(download));
                assertTrue(download.contentDisposition().isPresent());
                assertFalse(download.requestId().isBlank());
            }
            assertEquals(privateContent.length, response(client.objects().head(bucket, "single.txt")).contentLength()
                    .orElseThrow());

            var signed = response(client.signing().signDownload(
                    SigningClient.SignDownloadRequest.of(bucket, "single.txt", Duration.ofMinutes(2))));
            assertFalse(signed.path().isAbsolute());
            try (DownloadResponse download = client.objects().downloadSigned(signed.path())) {
                assertEquals("private-object-content", utf8(download));
            }

            assertEquals(Visibility.PUBLIC,
                    response(client.objects().updateVisibility(bucket, "single.txt", Visibility.PUBLIC)).visibility());

            var entries = response(client.explorer().listEntries(ExplorerClient.ListEntriesRequest.builder(bucket)
                    .prefix("tree/")
                    .limit(1)
                    .sortBy(ExplorerClient.SortBy.NAME)
                    .sortOrder(ExplorerClient.SortOrder.ASC)
                    .build()));
            assertFalse(entries.items().isEmpty());
            try (DownloadResponse archive = client.explorer().downloadFolderArchive(bucket, "tree/")) {
                byte[] zip = archive.body().readNBytes(4);
                assertEquals('P', zip[0]);
                assertEquals('K', zip[1]);
            }

            ExplorerClient.BatchDeleteResult explorerDelete = response(client.explorer().deleteEntries(
                    bucket, List.of(new ExplorerClient.DeleteItem(EntryType.FILE, "batch-delete.txt"))));
            assertEquals(1, explorerDelete.deletedCount());
            assertEquals(0, explorerDelete.failedCount());

            response(client.objects().upload(ObjectClient.UploadObjectRequest
                    .builder(bucket, "recycle-restore.txt", bytes("recycle.txt", "text/plain", "restore-me"))
                    .build()));
            response(client.objects().delete(bucket, "recycle-restore.txt"));
            long firstRecycleId = recycleId(client, bucket, "recycle-restore.txt");
            RecycleBinClient.RestoreResult restore = response(client.recycleBin().restore(List.of(firstRecycleId)));
            assertEquals(1, restore.restoredCount());
            assertEquals(0, restore.failedCount());
            response(client.objects().delete(bucket, "recycle-restore.txt"));
            long secondRecycleId = recycleId(client, bucket, "recycle-restore.txt");
            RecycleBinClient.DeleteResult permanentDelete = response(
                    client.recycleBin().deletePermanently(List.of(secondRecycleId)));
            assertEquals(1, permanentDelete.deletedCount());
            assertEquals(0, permanentDelete.failedCount());

            uploadPublic(client, bucket, "manual/index.html", "<html>manual</html>");
            uploadPublic(client, bucket, "manual/assets/app.js", "console.log('manual')");
            SiteClient.Site manualSite = response(client.sites().create(SiteClient.SiteRequest.builder(bucket)
                    .rootPrefix("manual/")
                    .domains(List.of(manualDomain))
                    .build()));
            siteIds.add(manualSite.id());
            assertEquals(manualSite.id(), response(client.sites().get(manualSite.id())).id());
            assertTrue(response(client.sites().list()).stream().anyMatch(site -> site.id() == manualSite.id()));
            SiteClient.Site updatedSite = response(client.sites().update(manualSite.id(),
                    SiteClient.SiteRequest.builder(bucket)
                            .rootPrefix("manual/")
                            .spaFallback(true)
                            .domains(List.of(manualDomain))
                            .build()));
            assertTrue(updatedSite.spaFallback());

            try (DownloadResponse root = client.sites().downloadPublished(manualSite.id(), "")) {
                assertEquals("<html>manual</html>", utf8(root));
            }
            assertEquals("text/html; charset=utf-8",
                    response(client.sites().headPublished(manualSite.id(), "")).contentType().orElseThrow());
            try (DownloadResponse asset = client.sites().downloadPublished(manualSite.id(), "assets/app.js")) {
                assertEquals("console.log('manual')", utf8(asset));
            }
            assertTrue(response(client.sites().headPublished(manualSite.id(), "assets/app.js"))
                    .contentLength().orElseThrow() > 0);
            verifyCustomDomainRoute(baseUri, manualDomain, "<html>manual</html>");

            String batchDomain = "batch-" + suffix + ".sdk.test";
            SiteClient.PublishResult published = response(client.sites().publish(
                    SiteClient.PublishRequest.builder(bucket, List.of(batchDomain), List.of(
                                    item("dist/index.html", "<html>batch</html>"),
                                    item("dist/assets/app.js", "console.log('batch')")))
                            .parentPrefix("published/")
                            .build()));
            assertEquals(2, published.uploadedCount());
            siteIds.add(published.site().id());

            SiteClient.Site fileSite = response(client.sites().publishFile(
                    SiteClient.PublishFileRequest.builder(
                                    bucket,
                                    List.of("file-" + suffix + ".sdk.test"),
                                    bytes("index.html", "text/html; charset=utf-8", "<html>file</html>"))
                            .parentPrefix("file-site/")
                            .build()));
            siteIds.add(fileSite.id());

            uploadPublic(client, bucket, "object-site/index.html", "<html>object</html>");
            SiteClient.Site objectSite = response(client.sites().publishObject(
                    SiteClient.PublishObjectRequest.builder(
                                    bucket,
                                    "object-site/index.html",
                                    List.of("object-" + suffix + ".sdk.test"))
                            .build()));
            siteIds.add(objectSite.id());

            for (Long siteId : List.copyOf(siteIds)) {
                response(client.sites().delete(siteId));
                siteIds.remove(siteId);
            }

            response(client.buckets().delete(bucket));
            bucketCreated = false;
            assertFalse(response(client.buckets().list(bucket)).stream()
                    .anyMatch(item -> item.name().equals(bucket)));
        } finally {
            if (bucketCreated) {
                try (LightOssClient cleanup = LightOssClient.builder(baseUri)
                        .bearerToken(token)
                        .requestIdProvider(new PacedRequestIds(Duration.ofMillis(225)))
                        .requestTimeout(Duration.ofSeconds(30))
                        .build()) {
                    cleanup.buckets().delete(bucket);
                } catch (RuntimeException ignored) {
                    // Preserve the original test failure; a subsequent run uses a distinct bucket.
                }
            }
        }
    }

    private static void verifyCustomDomainRoute(URI baseUri, String domain, String expected) throws Exception {
        HttpClient proxyClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.of(new InetSocketAddress(baseUri.getHost(), baseUri.getPort())))
                .build();
        try (proxyClient;
             LightOssClient domainClient = LightOssClient.builder(baseUri).httpClient(proxyClient).build();
             DownloadResponse response = domainClient.sites().downloadDomain(URI.create("http://" + domain + "/"))) {
            assertEquals(expected, utf8(response));
            assertTrue(domainClient.sites().headDomain(URI.create("http://" + domain + "/"))
                    .data().contentLength().orElseThrow() > 0);
        }
    }

    private static long recycleId(LightOssClient client, String bucket, String key) {
        return response(client.recycleBin().list(RecycleBinClient.ListRequest.builder()
                        .bucket(bucket)
                        .build()))
                .items().stream()
                .filter(item -> item.objectKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("recycle item not found: " + key))
                .id();
    }

    private static void uploadPublic(LightOssClient client, String bucket, String key, String content) {
        response(client.objects().upload(ObjectClient.UploadObjectRequest
                .builder(bucket, key, bytes(key.substring(key.lastIndexOf('/') + 1),
                        contentType(key), content))
                .visibility(Visibility.PUBLIC)
                .build()));
    }

    private static ObjectClient.UploadItem item(String path, String content) {
        return new ObjectClient.UploadItem(path,
                bytes(path.substring(path.lastIndexOf('/') + 1), contentType(path), content));
    }

    private static UploadSource bytes(String filename, String contentType, String content) {
        return bytes(filename, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    private static UploadSource bytes(String filename, String contentType, byte[] content) {
        return UploadSource.fromBytes(filename, contentType, content);
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript";
        }
        return "text/plain";
    }

    private static String utf8(DownloadResponse response) throws Exception {
        return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static <T> T response(LightOssResponse<T> response) {
        assertFalse(response.requestId().isBlank());
        return response.data();
    }

    private static final class PacedRequestIds implements Supplier<String> {
        private final long intervalNanos;
        private long nextRequestNanos;

        private PacedRequestIds(Duration interval) {
            intervalNanos = interval.toNanos();
        }

        @Override
        public synchronized String get() {
            while (nextRequestNanos > System.nanoTime()) {
                LockSupport.parkNanos(nextRequestNanos - System.nanoTime());
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while pacing integration requests");
                }
            }
            nextRequestNanos = System.nanoTime() + intervalNanos;
            return UUID.randomUUID().toString();
        }
    }
}
