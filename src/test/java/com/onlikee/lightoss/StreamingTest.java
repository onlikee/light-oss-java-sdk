package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onlikee.lightoss.exception.LightOssTransportException;
import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.transfer.ContentMetadata;
import com.onlikee.lightoss.transfer.DownloadResponse;
import com.onlikee.lightoss.transfer.UploadSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StreamingTest {
    private static final String OBJECT = "{\"id\":1,\"bucket_name\":\"demo\",\"object_key\":\"large.bin\","
            + "\"original_filename\":\"large.bin\",\"size\":4194304,\"content_type\":\"application/octet-stream\","
            + "\"etag\":\"etag\",\"visibility\":\"private\",\"created_at\":\"2026-01-01T00:00:00Z\","
            + "\"updated_at\":\"2026-01-01T00:00:00Z\"}";

    @Test
    void largeSupplierUploadIsIncrementalAndSdkClosesTheStream() throws Exception {
        byte[] content = new byte[4 * 1024 * 1024];
        AtomicReference<TrackingInputStream> opened = new AtomicReference<>();
        UploadSource source = UploadSource.fromInputStream(
                "large.bin", "application/octet-stream", content.length,
                () -> {
                    TrackingInputStream stream = new TrackingInputStream(content);
                    opened.set(stream);
                    return stream;
                });

        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri()).bearerToken("token").build()) {
            server.json(201, OBJECT);
            client.objects().upload(ObjectClient.UploadObjectRequest.builder("demo", "large.bin", source).build());
            assertEquals(Integer.toString(content.length), server.lastRequest().header("Content-Length"));
        }
        assertTrue(opened.get().closed.get());
        assertTrue(opened.get().maximumReadRequest < content.length);
    }

    @Test
    void multipartContainsGeneratedManifestAndClosesSupplierStreams() throws Exception {
        AtomicReference<TrackingInputStream> opened = new AtomicReference<>();
        UploadSource source = UploadSource.fromInputStream("unicodé.txt", "text/plain", 3, () -> {
            TrackingInputStream stream = new TrackingInputStream(new byte[] {1, 2, 3});
            opened.set(stream);
            return stream;
        });
        ObjectClient.UploadItem item = new ObjectClient.UploadItem("folder/中文.txt", source);

        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri()).bearerToken("token").build()) {
            server.json(201, "{\"uploaded_count\":1,\"items\":[" + OBJECT + "]}");
            client.objects().uploadBatch(ObjectClient.UploadBatchRequest.builder("demo", List.of(item)).build());
            String contentType = server.lastRequest().header("Content-Type");
            String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
            String body = server.lastRequest().bodyText();
            assertTrue(body.contains("name=\"manifest\""));
            assertTrue(body.contains("\"file_field\":\"file_0\""));
            assertTrue(body.contains("\"relative_path\":\"folder/中文.txt\""));
            assertTrue(body.contains("filename*=UTF-8''unicod%C3%A9.txt"));
            assertTrue(body.endsWith("--" + boundary + "--\r\n"));
        }
        assertTrue(opened.get().closed.get());
    }

    @Test
    void batchLimitsAreCheckedBeforeNetworkIo() {
        UploadSource source = UploadSource.fromBytes("a", "text/plain", new byte[0]);
        List<ObjectClient.UploadItem> tooMany = IntStream.range(0, 2001)
                .mapToObj(index -> new ObjectClient.UploadItem("f" + index, source))
                .toList();
        assertThrows(LightOssValidationException.class,
                () -> ObjectClient.UploadBatchRequest.builder("demo", tooMany).build());
        assertThrows(LightOssValidationException.class,
                () -> SiteClient.PublishRequest.builder("demo", List.of("demo.localhost"), tooMany).build());
    }

    @Test
    void closingDownloadResponseClosesBodyImmediately() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        ContentMetadata metadata = new ContentMetadata(
                OptionalLong.of(3), Optional.of("application/octet-stream"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        DownloadResponse response = new DownloadResponse(body, metadata, "request-id");
        response.close();
        assertTrue(closed.get());
    }

    @Test
    void uploadStreamFailureBecomesTransportExceptionWithRequestId() throws Exception {
        UploadSource source = UploadSource.fromInputStream("broken.bin", "application/octet-stream", () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken source");
            }
        });
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri())
                     .bearerToken("token").requestIdProvider(() -> "upload-id").build()) {
            server.json(201, OBJECT);
            LightOssTransportException exception = assertThrows(
                    LightOssTransportException.class,
                    () -> client.objects().upload(
                            ObjectClient.UploadObjectRequest.builder("demo", "broken.bin", source).build()));
            assertEquals("upload-id", exception.requestId().orElseThrow());
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();
        private int maximumReadRequest;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            maximumReadRequest = Math.max(maximumReadRequest, length);
            return super.read(target, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }
}
