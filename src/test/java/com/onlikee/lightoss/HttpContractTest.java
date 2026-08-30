package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onlikee.lightoss.model.EntryType;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.DownloadResponse;
import com.onlikee.lightoss.transfer.UploadSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpContractTest {
    private static final String TIME = "2026-01-01T00:00:00Z";
    private static final String OBJECT = "{\"id\":1,\"bucket_name\":\"demo\",\"object_key\":\"dir/a.txt\","
            + "\"original_filename\":\"a.txt\",\"size\":2,\"content_type\":\"text/plain\","
            + "\"etag\":\"etag\",\"visibility\":\"private\",\"created_at\":\"" + TIME
            + "\",\"updated_at\":\"" + TIME + "\"}";
    private static final String SITE = "{\"id\":1,\"bucket\":\"demo\",\"root_prefix\":\"dist/\","
            + "\"enabled\":true,\"index_document\":\"index.html\",\"error_document\":\"\","
            + "\"spa_fallback\":true,\"domains\":[\"demo.localhost\"],\"created_at\":\"" + TIME
            + "\",\"updated_at\":\"" + TIME + "\"}";

    @Test
    void coversEveryOpenApiOperationAndHostRoutingExtension() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = client(server)) {
            server.json(200, "{\"status\":\"ok\",\"version\":\"v1\"}");
            client.health().liveness();
            assertWire(server, "GET", "/livez", false);

            server.json(200, "{\"status\":\"ready\",\"version\":\"v1\"}");
            client.health().readiness();
            assertWire(server, "GET", "/readyz", false);

            server.response(200, "root".getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "text/html"));
            try (DownloadResponse response = client.sites().downloadPublished(1, "")) {
                assertEquals("root", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            assertWire(server, "GET", "/sites/1", false);

            server.response(200, new byte[0], Map.of("Content-Type", "text/html"));
            client.sites().headPublished(1, "");
            assertWire(server, "HEAD", "/sites/1", false);

            server.response(200, "path".getBytes(StandardCharsets.UTF_8), Map.of());
            try (DownloadResponse ignored = client.sites().downloadPublished(1, "assets/app.js")) {
                assertNotNull(ignored);
            }
            assertWire(server, "GET", "/sites/1/assets/app.js", false);

            server.response(200, new byte[0], Map.of("ETag", "site-etag"));
            client.sites().headPublished(1, "assets/app.js");
            assertWire(server, "HEAD", "/sites/1/assets/app.js", false);

            server.json(200, "{\"status\":{\"service\":\"ok\",\"db\":\"ok\"},\"version\":\"v1\"}");
            client.health().health();
            assertWire(server, "GET", "/api/v1/healthz", true);

            server.json(200, metrics());
            client.system().metrics();
            assertWire(server, "GET", "/api/v1/system/metrics", true);

            server.json(200, stats());
            client.system().stats();
            assertWire(server, "GET", "/api/v1/system/stats", true);

            server.json(200, storage());
            client.system().updateStorageQuota(1000);
            assertWire(server, "PUT", "/api/v1/system/storage/quota", true);
            assertTrue(server.lastRequest().bodyText().contains("\"max_bytes\":1000"));

            server.json(200, "{\"items\":[]}");
            client.buckets().list("unicodé search");
            assertWire(server, "GET", "/api/v1/buckets?search=unicod%C3%A9%20search", true);

            server.json(201, bucket());
            client.buckets().create("demo");
            assertWire(server, "POST", "/api/v1/buckets", true);

            noContent(server);
            client.buckets().delete("demo");
            assertWire(server, "DELETE", "/api/v1/buckets/demo", true);

            server.json(200, "{\"items\":[]}");
            client.explorer().listFolders("demo");
            assertWire(server, "GET", "/api/v1/buckets/demo/folders", true);

            server.json(201, folder());
            client.explorer().createFolder(new ExplorerClient.CreateFolderRequest("demo", "docs/", "api"));
            assertWire(server, "POST", "/api/v1/buckets/demo/folders", true);

            noContent(server);
            client.explorer().deleteFolder(new ExplorerClient.DeleteFolderRequest("demo", "docs/api/", true));
            assertWire(server, "DELETE", "/api/v1/buckets/demo/folders?path=docs%2Fapi%2F&recursive=true", true);

            server.response(200, "zip".getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/zip"));
            try (DownloadResponse ignored = client.explorer().downloadFolderArchive("demo", "docs/")) {
                assertNotNull(ignored);
            }
            assertWire(server, "GET", "/api/v1/buckets/demo/folders/archive?path=docs%2F", true);

            server.json(200, explorerPage());
            client.explorer().listEntries(ExplorerClient.ListEntriesRequest.builder("demo").build());
            assertWire(server, "GET", "/api/v1/buckets/demo/entries?limit=100&sort_by=name&sort_order=asc", true);

            server.json(200, "{\"deleted_count\":1,\"failed_count\":0,\"failed_items\":[]}");
            client.explorer().deleteEntries("demo", List.of(new ExplorerClient.DeleteItem(EntryType.FILE, "a.txt")));
            assertWire(server, "POST", "/api/v1/buckets/demo/entries/batch-delete", true);

            server.json(200, "{\"items\":[" + OBJECT + "],\"next_cursor\":\"next\"}");
            client.objects().list(ObjectClient.ListObjectsRequest.builder("demo").prefix("dir/").build());
            assertWire(server, "GET", "/api/v1/buckets/demo/objects?prefix=dir%2F&limit=100", true);

            server.json(201, "{\"uploaded_count\":1,\"items\":[" + OBJECT + "]}");
            client.objects().uploadBatch(ObjectClient.UploadBatchRequest.builder(
                    "demo", List.of(item("a.txt"))).build());
            assertWire(server, "POST", "/api/v1/buckets/demo/objects/batch", true);
            assertTrue(server.lastRequest().header("Content-Type").startsWith("multipart/form-data; boundary="));

            server.response(200, "hi".getBytes(StandardCharsets.UTF_8), downloadHeaders());
            try (DownloadResponse response = client.objects().download(
                    ObjectClient.DownloadObjectRequest.builder("demo", "dir/中文.txt").forceDownload(true).build())) {
                assertEquals("hi", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("中文.txt", response.originalFilename().orElseThrow());
            }
            assertWire(server, "GET", "/api/v1/buckets/demo/objects/dir/%E4%B8%AD%E6%96%87.txt?download=true", true);

            server.response(200, new byte[0], downloadHeaders());
            client.objects().head("demo", "dir/a.txt");
            assertWire(server, "HEAD", "/api/v1/buckets/demo/objects/dir/a.txt", true);

            server.json(201, OBJECT);
            client.objects().upload(ObjectClient.UploadObjectRequest.builder(
                    "demo", "dir/a.txt", UploadSource.fromBytes("中文.txt", "text/plain", new byte[] {1, 2})).build());
            assertWire(server, "PUT", "/api/v1/buckets/demo/objects/dir/a.txt", true);
            assertEquals("%E4%B8%AD%E6%96%87.txt", server.lastRequest().header("X-Original-Filename"));

            noContent(server);
            client.objects().delete("demo", "dir/a.txt");
            assertWire(server, "DELETE", "/api/v1/buckets/demo/objects/dir/a.txt", true);

            server.json(200, OBJECT.replace("\"private\"", "\"public\""));
            client.objects().updateVisibility("demo", "dir/a.txt", Visibility.PUBLIC);
            assertWire(server, "PATCH", "/api/v1/buckets/demo/objects/visibility/dir/a.txt", true);

            server.json(200, "{\"items\":[],\"next_cursor\":\"\"}");
            client.recycleBin().list(RecycleBinClient.ListRequest.builder().bucket("demo").build());
            assertWire(server, "GET", "/api/v1/recycle-bin/objects?bucket=demo&limit=100", true);

            server.json(200, "{\"restored_count\":1,\"failed_count\":0,\"failed_items\":[]}");
            client.recycleBin().restore(List.of(1L));
            assertWire(server, "POST", "/api/v1/recycle-bin/objects/restore", true);

            server.json(200, "{\"deleted_count\":1,\"failed_count\":0,\"failed_items\":[]}");
            client.recycleBin().deletePermanently(List.of(1L));
            assertWire(server, "POST", "/api/v1/recycle-bin/objects/batch-delete", true);

            server.json(200, "{\"items\":[" + SITE + "]}");
            client.sites().list();
            assertWire(server, "GET", "/api/v1/sites", true);

            server.json(201, SITE);
            client.sites().create(SiteClient.SiteRequest.builder("demo").domains(List.of("demo.localhost")).build());
            assertWire(server, "POST", "/api/v1/sites", true);

            server.json(201, "{\"uploaded_count\":1,\"site\":" + SITE + "}");
            client.sites().publish(SiteClient.PublishRequest.builder(
                    "demo", List.of("demo.localhost"), List.of(item("dist/index.html"))).build());
            assertWire(server, "POST", "/api/v1/sites/publish", true);

            server.json(201, SITE);
            client.sites().publishObject(SiteClient.PublishObjectRequest.builder(
                    "demo", "index.html", List.of("demo.localhost")).build());
            assertWire(server, "POST", "/api/v1/sites/publish/object", true);

            server.json(201, SITE);
            client.sites().publishFile(SiteClient.PublishFileRequest.builder(
                    "demo", List.of("demo.localhost"), UploadSource.fromBytes("index.html", "text/html", new byte[] {1})).build());
            assertWire(server, "POST", "/api/v1/sites/publish/file", true);

            server.json(200, SITE);
            client.sites().get(1);
            assertWire(server, "GET", "/api/v1/sites/1", true);

            server.json(200, SITE);
            client.sites().update(1, SiteClient.SiteRequest.builder("demo").build());
            assertWire(server, "PUT", "/api/v1/sites/1", true);

            noContent(server);
            client.sites().delete(1);
            assertWire(server, "DELETE", "/api/v1/sites/1", true);

            server.json(200, "{\"path\":\"/api/v1/buckets/demo/objects/a.txt?expires=1&signature=x\",\"expires_at\":1893456000}");
            client.signing().signDownload(SigningClient.SignDownloadRequest.of("demo", "a.txt", Duration.ofMinutes(5)));
            assertWire(server, "POST", "/api/v1/sign/download", true);

            server.response(200, "domain".getBytes(StandardCharsets.UTF_8), Map.of());
            try (DownloadResponse ignored = client.sites().downloadDomain(server.baseUri().resolve("custom/path"))) {
                assertNotNull(ignored);
            }
            assertWire(server, "GET", "/custom/path", false);

            server.response(200, new byte[0], Map.of());
            client.sites().headDomain(server.baseUri().resolve("custom/path"));
            assertWire(server, "HEAD", "/custom/path", false);
        }
    }

    private static LightOssClient client(TestHttpServer server) {
        AtomicInteger ids = new AtomicInteger();
        return LightOssClient.builder(server.baseUri())
                .bearerToken("token")
                .requestIdProvider(() -> "rid-" + ids.incrementAndGet())
                .build();
    }

    private static void assertWire(TestHttpServer server, String method, String pathAndQuery, boolean authenticated) {
        TestHttpServer.Request request = server.lastRequest();
        assertEquals(method, request.method());
        assertEquals(pathAndQuery, request.uri().toString());
        assertNotNull(request.header("X-Request-ID"));
        assertNotNull(request.header("User-Agent"));
        if (authenticated) {
            assertEquals("Bearer token", request.header("Authorization"));
        } else {
            assertNull(request.header("Authorization"));
        }
    }

    private static void noContent(TestHttpServer server) {
        server.response(204, new byte[0], Map.of());
    }

    private static ObjectClient.UploadItem item(String path) {
        return new ObjectClient.UploadItem(
                path, UploadSource.fromBytes(path.substring(path.lastIndexOf('/') + 1), "text/plain", new byte[] {1}));
    }

    private static Map<String, String> downloadHeaders() {
        return Map.of(
                "Content-Type", "text/plain",
                "ETag", "etag",
                "X-Object-Visibility", "private",
                "X-Original-Filename", "%E4%B8%AD%E6%96%87.txt",
                "Content-Disposition", "attachment");
    }

    private static String bucket() {
        return "{\"id\":1,\"name\":\"demo\",\"created_at\":\"" + TIME
                + "\",\"updated_at\":\"" + TIME + "\"}";
    }

    private static String folder() {
        return "{\"path\":\"docs/api/\",\"name\":\"api\",\"parent_path\":\"docs/\"}";
    }

    private static String explorerPage() {
        return "{\"items\":[{\"type\":\"directory\",\"path\":\"docs/\",\"name\":\"docs\","
                + "\"is_empty\":false,\"object_key\":null,\"original_filename\":null,\"size\":null,"
                + "\"content_type\":null,\"etag\":null,\"visibility\":null,\"created_at\":null,\"updated_at\":null},"
                + "{\"type\":\"file\",\"path\":\"a.txt\",\"name\":\"a.txt\",\"is_empty\":null,"
                + "\"object_key\":\"a.txt\",\"original_filename\":\"a.txt\",\"size\":2,"
                + "\"content_type\":\"text/plain\",\"etag\":\"etag\",\"visibility\":\"private\","
                + "\"created_at\":\"" + TIME + "\",\"updated_at\":\"" + TIME + "\"}],\"next_cursor\":\"\"}";
    }

    private static String storage() {
        return "{\"root_path\":\"/data\",\"used_bytes\":10,\"max_bytes\":1000,"
                + "\"remaining_bytes\":990,\"used_percent\":1.0,\"limit_status\":\"ok\"}";
    }

    private static String stats() {
        return "{\"os\":\"windows\",\"cpu\":{\"used_percent\":1.0},\"memory\":{\"total_bytes\":100,"
                + "\"used_bytes\":10,\"available_bytes\":90,\"used_percent\":10.0},\"disks\":[],"
                + "\"storage\":" + storage() + "}";
    }

    private static String metrics() {
        String limiter = "{\"backend\":\"memory\",\"entries\":0,\"max_entries\":100,"
                + "\"expired_evictions\":0,\"capacity_evictions\":0,\"capacity_rejections\":0,"
                + "\"rejected_requests\":0,\"store_errors\":0}";
        return "{\"uploads\":{\"staged\":0,\"failed\":0,\"bytes\":0,\"staging_duration_ns\":0,"
                + "\"reservation_failures\":0},\"transactions\":{\"completed\":0,\"failed\":0,\"duration_ns\":0},"
                + "\"cleanup\":{\"completed\":0,\"failed\":0,\"backlog\":0},"
                + "\"quota\":{\"used_bytes\":0,\"reserved_bytes\":0,\"max_bytes\":1000,\"remaining_bytes\":1000},"
                + "\"database\":{\"max_open_connections\":10,\"open_connections\":1,\"in_use\":0,\"idle\":1,"
                + "\"wait_count\":0,\"wait_duration_ns\":0,\"max_idle_closed\":0,\"max_idle_time_closed\":0,"
                + "\"max_lifetime_closed\":0},\"rate_limit\":{\"ip\":" + limiter + ",\"public\":" + limiter
                + ",\"management\":" + limiter + ",\"upload\":" + limiter + ",\"sign\":" + limiter
                + ",\"health\":" + limiter + "}}";
    }
}
