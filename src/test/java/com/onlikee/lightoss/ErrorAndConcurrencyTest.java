package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onlikee.lightoss.exception.LightOssApiException;
import com.onlikee.lightoss.exception.LightOssConfigurationException;
import com.onlikee.lightoss.exception.LightOssProtocolException;
import com.onlikee.lightoss.exception.LightOssTimeoutException;
import com.onlikee.lightoss.exception.LightOssTransportException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ErrorAndConcurrencyTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 404, 409, 413, 429, 503})
    void mapsBackendErrorEnvelopes(int status) throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.error(status, "backend_code", "backend message");
            LightOssApiException exception = assertThrows(
                    LightOssApiException.class, () -> client.buckets().list());
            assertEquals(status, exception.statusCode());
            assertEquals("backend_code", exception.code());
            assertEquals("backend message", exception.serviceMessage());
            assertEquals("request-1", exception.requestId().orElseThrow());
        }
    }

    @Test
    void bodylessPublicSiteErrorHasExplicitSdkCodeAndNoCredential() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.response(404, new byte[0], Map.of());
            LightOssApiException exception = assertThrows(
                    LightOssApiException.class, () -> client.sites().downloadDomain(server.baseUri()));
            assertEquals("sdk_http_error", exception.code());
            assertEquals(404, exception.statusCode());
            assertEquals(null, server.lastRequest().header("Authorization"));
        }
    }

    @Test
    void detectsMalformedJsonAndRequestIdMismatch() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.response(200, "not-json".getBytes(StandardCharsets.UTF_8), Map.of());
            assertThrows(LightOssProtocolException.class, () -> client.buckets().list());

            server.response(200,
                    "{\"request_id\":\"different\",\"data\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Type", "application/json"));
            assertThrows(LightOssProtocolException.class, () -> client.buckets().list());
        }
    }

    @Test
    void degradedHealthDataAndRateLimiterErrorShareStatusWithoutAmbiguity() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.json(503, "{\"status\":{\"service\":\"ok\",\"db\":\"error\"},\"version\":\"v1\"}");
            assertTrue(!client.health().health().data().healthy());

            server.error(503, "rate_limit_unavailable", "unavailable");
            LightOssApiException exception = assertThrows(
                    LightOssApiException.class, () -> client.health().health());
            assertEquals("rate_limit_unavailable", exception.code());
        }
    }

    @Test
    void protectedCallsFailBeforeSendingWhenCredentialIsUnavailable() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri())
                     .requestIdProvider(() -> "local-id").build()) {
            LightOssConfigurationException exception = assertThrows(
                    LightOssConfigurationException.class, () -> client.buckets().list());
            assertEquals("local-id", exception.requestId().orElseThrow());
            assertTrue(server.requests().isEmpty());
        }

        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri())
                     .tokenProvider(() -> { throw new IllegalStateException("boom"); })
                     .requestIdProvider(() -> "provider-id").build()) {
            LightOssConfigurationException exception = assertThrows(
                    LightOssConfigurationException.class, () -> client.buckets().list());
            assertEquals("provider-id", exception.requestId().orElseThrow());
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void publicObjectGetAndHeadCanRunWithoutBearer() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri())
                     .requestIdProvider(() -> "public-id").build()) {
            server.response(200, "public".getBytes(StandardCharsets.UTF_8), Map.of());
            try (var download = client.objects().download(
                    ObjectClient.DownloadObjectRequest.builder("demo", "public.txt").build())) {
                assertEquals("public", new String(download.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(null, server.lastRequest().header("Authorization"));
            server.response(200, new byte[0], Map.of("Content-Type", "text/plain"));
            client.objects().head("demo", "public.txt");
            assertEquals(null, server.lastRequest().header("Authorization"));
        }
    }

    @Test
    void signedDownloadSuppressesConfiguredBearer() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.response(200, "signed".getBytes(StandardCharsets.UTF_8), Map.of());
            try (var download = client.objects().downloadSigned(
                    java.net.URI.create("/api/v1/buckets/demo/objects/a.txt?expires=1&signature=x"))) {
                assertEquals("signed", new String(download.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(null, server.lastRequest().header("Authorization"));
        }
    }

    @Test
    void defaultClientDoesNotFollowRedirects() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.response(302, new byte[0], Map.of("Location", server.baseUri().resolve("redirected").toString()));
            LightOssApiException exception = assertThrows(
                    LightOssApiException.class, () -> client.sites().downloadDomain(server.baseUri()));
            assertEquals(302, exception.statusCode());
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void requestTimeoutUsesDedicatedExceptionAndKeepsGeneratedId() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = LightOssClient.builder(server.baseUri())
                     .bearerToken("token")
                     .requestIdProvider(() -> "timeout-id")
                     .requestTimeout(Duration.ofMillis(40))
                     .build()) {
            server.delayedResponse(200,
                    "{\"request_id\":\"timeout-id\",\"data\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8),
                    300);
            LightOssTimeoutException exception = assertThrows(
                    LightOssTimeoutException.class, () -> client.buckets().list());
            assertEquals("timeout-id", exception.requestId().orElseThrow());
        }
    }

    @Test
    void interruptionRestoresInterruptedFlag() throws Exception {
        try (TestHttpServer server = new TestHttpServer();
             LightOssClient client = authorized(server)) {
            server.delayedResponse(200,
                    "{\"request_id\":\"request-1\",\"data\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8),
                    1000);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptedInCatch = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            Thread thread = Thread.ofVirtual().start(() -> {
                started.countDown();
                try {
                    client.buckets().list();
                } catch (Throwable exception) {
                    failure.set(exception);
                    interruptedInCatch.set(Thread.currentThread().isInterrupted());
                }
            });
            started.await();
            while (server.requests().isEmpty()) {
                Thread.sleep(5);
            }
            thread.interrupt();
            thread.join(2000);
            assertInstanceOf(LightOssTransportException.class, failure.get());
            assertTrue(interruptedInCatch.get());
        }
    }

    @Test
    void sharedClientSupportsVirtualThreadConcurrencyAndDynamicProviders() throws Exception {
        int calls = 40;
        try (TestHttpServer server = new TestHttpServer()) {
            for (int index = 0; index < calls; index++) {
                server.json(200, "{\"items\":[]}");
            }
            AtomicInteger tokens = new AtomicInteger();
            AtomicInteger ids = new AtomicInteger();
            try (LightOssClient client = LightOssClient.builder(server.baseUri())
                    .tokenProvider(() -> "token-" + tokens.incrementAndGet())
                    .requestIdProvider(() -> "request-" + ids.incrementAndGet())
                    .build();
                 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = IntStream.range(0, calls)
                        .mapToObj(index -> executor.submit(() -> client.buckets().list()))
                        .toList();
                for (var future : futures) {
                    assertNotNull(future.get());
                }
            }
            assertEquals(calls, server.requests().size());
            Set<String> requestIds = new HashSet<>();
            Set<String> authorization = new HashSet<>();
            for (TestHttpServer.Request request : server.requests()) {
                requestIds.add(request.header("X-Request-ID"));
                authorization.add(request.header("Authorization"));
            }
            assertEquals(calls, requestIds.size());
            assertEquals(calls, authorization.size());
        }
    }

    @Test
    void closeRejectsSdkCallsButDoesNotCloseInjectedHttpClient() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            HttpClient injected = HttpClient.newHttpClient();
            LightOssClient client = LightOssClient.builder(server.baseUri()).httpClient(injected).build();
            client.close();
            assertThrows(LightOssConfigurationException.class, () -> client.health().liveness());

            server.response(200, "ok".getBytes(StandardCharsets.UTF_8), Map.of());
            HttpResponse<String> response = injected.send(
                    HttpRequest.newBuilder(server.baseUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
            injected.close();
        }
    }

    private static LightOssClient authorized(TestHttpServer server) {
        AtomicInteger ids = new AtomicInteger();
        return LightOssClient.builder(server.baseUri())
                .bearerToken("token")
                .requestIdProvider(() -> "request-" + ids.incrementAndGet())
                .build();
    }
}
