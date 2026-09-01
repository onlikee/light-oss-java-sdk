package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.LightOssResponse;
import com.onlikee.lightoss.exception.LightOssApiException;
import com.onlikee.lightoss.exception.LightOssConfigurationException;
import com.onlikee.lightoss.exception.LightOssException;
import com.onlikee.lightoss.exception.LightOssProtocolException;
import com.onlikee.lightoss.exception.LightOssTransportException;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.ContentMetadata;
import com.onlikee.lightoss.transfer.DownloadResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

public final class ClientContext implements AutoCloseable {
    public enum AuthMode {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    @FunctionalInterface
    public interface DataParser<T> {
        T parse(JsonNode data, String requestId);
    }

    private final URI baseUri;
    private final HttpClient httpClient;
    private final boolean ownsHttpClient;
    private final Supplier<String> tokenProvider;
    private final Supplier<String> requestIdProvider;
    private final Duration requestTimeout;
    private final JsonCodec json = new JsonCodec();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ClientContext(
            URI baseUri,
            HttpClient httpClient,
            boolean ownsHttpClient,
            Supplier<String> tokenProvider,
            Supplier<String> requestIdProvider,
            Duration requestTimeout) {
        this.baseUri = baseUri;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
        this.tokenProvider = tokenProvider;
        this.requestIdProvider = requestIdProvider;
        this.requestTimeout = requestTimeout;
    }

    public URI baseUri() {
        return baseUri;
    }

    public JsonCodec json() {
        return json;
    }

    public HttpRequest.BodyPublisher jsonBody(Object value) {
        return HttpRequest.BodyPublishers.ofByteArray(json.write(value));
    }

    public <T> LightOssResponse<T> json(
            String method,
            URI uri,
            AuthMode authMode,
            HttpRequest.BodyPublisher body,
            String contentType,
            Map<String, String> headers,
            int successStatus,
            DataParser<T> parser) {
        return jsonStatuses(method, uri, authMode, body, contentType, headers, Set.of(successStatus), parser);
    }

    public <T> LightOssResponse<T> jsonStatuses(
            String method,
            URI uri,
            AuthMode authMode,
            HttpRequest.BodyPublisher body,
            String contentType,
            Map<String, String> headers,
            Set<Integer> successStatuses,
            DataParser<T> parser) {
        String requestId = nextRequestId();
        HttpRequest request = request(method, uri, authMode, body, contentType, headers, requestId);
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), requestId);
        if (!successStatuses.contains(response.statusCode())) {
            throw apiError(response.statusCode(), response.headers().firstValue("X-Request-ID"),
                    response.headers().firstValue("Content-Type"), response.body(), requestId);
        }
        JsonNode root = json.read(response.body(), requestId);
        if (!root.isObject()) {
            throw new LightOssProtocolException("response envelope is not an object", requestId);
        }
        String envelopeRequestId = requiredEnvelopeRequestId(root, requestId);
        verifyRequestIds(response.headers().firstValue("X-Request-ID"), envelopeRequestId, requestId);
        if (root.has("error")) {
            throw apiError(
                    response.statusCode(),
                    response.headers().firstValue("X-Request-ID"),
                    response.headers().firstValue("Content-Type"),
                    response.body(),
                    requestId);
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new LightOssProtocolException("response envelope does not contain data", envelopeRequestId);
        }
        try {
            return new LightOssResponse<>(parser.parse(data, envelopeRequestId), envelopeRequestId);
        } catch (LightOssException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LightOssProtocolException("failed to map response data", envelopeRequestId, exception);
        }
    }

    public LightOssResponse<Void> noContent(
            String method,
            URI uri,
            AuthMode authMode,
            Map<String, String> headers,
            int successStatus) {
        String requestId = nextRequestId();
        HttpRequest request = request(
                method,
                uri,
                authMode,
                HttpRequest.BodyPublishers.noBody(),
                null,
                headers,
                requestId);
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), requestId);
        if (response.statusCode() != successStatus) {
            throw apiError(response.statusCode(), response.headers().firstValue("X-Request-ID"),
                    response.headers().firstValue("Content-Type"), response.body(), requestId);
        }
        if (response.body().length != 0) {
            throw new LightOssProtocolException("no-content response unexpectedly contains a body", requestId);
        }
        String responseRequestId = requiredHeaderRequestId(response.headers().firstValue("X-Request-ID"), requestId);
        return new LightOssResponse<>(null, responseRequestId);
    }

    public DownloadResponse stream(
            String method,
            URI uri,
            AuthMode authMode,
            Map<String, String> headers,
            int successStatus) {
        String requestId = nextRequestId();
        HttpRequest request = request(
                method,
                uri,
                authMode,
                HttpRequest.BodyPublishers.noBody(),
                null,
                headers,
                requestId);
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), requestId);
        if (response.statusCode() != successStatus) {
            try (InputStream body = response.body()) {
                throw apiError(
                        response.statusCode(),
                        response.headers().firstValue("X-Request-ID"),
                        response.headers().firstValue("Content-Type"),
                        body.readAllBytes(),
                        requestId);
            } catch (LightOssException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new LightOssTransportException("failed to read error response body", requestId, exception);
            }
        }
        String responseRequestId = requiredHeaderRequestId(response.headers().firstValue("X-Request-ID"), requestId);
        try {
            return new DownloadResponse(response.body(), contentMetadata(response, responseRequestId), responseRequestId);
        } catch (RuntimeException exception) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Preserve the contract error that prevented construction.
            }
            throw exception;
        }
    }

    public LightOssResponse<ContentMetadata> head(
            URI uri,
            AuthMode authMode,
            Map<String, String> headers,
            int successStatus) {
        String requestId = nextRequestId();
        HttpRequest request = request(
                "HEAD",
                uri,
                authMode,
                HttpRequest.BodyPublishers.noBody(),
                null,
                headers,
                requestId);
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), requestId);
        if (response.statusCode() != successStatus) {
            throw apiError(response.statusCode(), response.headers().firstValue("X-Request-ID"),
                    response.headers().firstValue("Content-Type"), response.body(), requestId);
        }
        String responseRequestId = requiredHeaderRequestId(response.headers().firstValue("X-Request-ID"), requestId);
        return new LightOssResponse<>(contentMetadata(response, responseRequestId), responseRequestId);
    }

    private HttpRequest request(
            String method,
            URI uri,
            AuthMode authMode,
            HttpRequest.BodyPublisher body,
            String contentType,
            Map<String, String> headers,
            String requestId) {
        if (closed.get()) {
            throw new LightOssConfigurationException("LightOssClient is closed");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method(method, body)
                .header("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.1")
                .header("User-Agent", "light-oss-sdk/0.1.0")
                .header("X-Request-ID", requestId);
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        headers.forEach((name, value) -> builder.header(name, Checks.headerValue(value, name)));
        if (authMode != AuthMode.NONE) {
            String token = token(authMode == AuthMode.REQUIRED, requestId);
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        return builder.build();
    }

    private String token(boolean required, String requestId) {
        if (tokenProvider == null) {
            if (required) {
                throw new LightOssConfigurationException("a bearer token is required for this operation", requestId, null);
            }
            return null;
        }
        try {
            String token = tokenProvider.get();
            if (token == null || token.isBlank()) {
                if (required) {
                    throw new LightOssConfigurationException("tokenProvider returned a blank token", requestId, null);
                }
                return null;
            }
            return Checks.headerValue(token, "bearer token");
        } catch (LightOssConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LightOssConfigurationException("tokenProvider failed", requestId, exception);
        }
    }

    private String nextRequestId() {
        if (closed.get()) {
            throw new LightOssConfigurationException("LightOssClient is closed");
        }
        try {
            return Checks.headerValue(requestIdProvider.get(), "request ID");
        } catch (LightOssConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LightOssConfigurationException("requestIdProvider failed", null, exception);
        }
    }

    private <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            String requestId) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LightOssTransportException("Light OSS request was interrupted", requestId, exception);
        } catch (HttpTimeoutException exception) {
            throw new com.onlikee.lightoss.exception.LightOssTimeoutException(
                    "Light OSS request timed out", requestId, exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof LightOssException lightOssException) {
                throw lightOssException;
            }
            throw new LightOssTransportException("Light OSS request failed", requestId, exception);
        }
    }

    private LightOssException apiError(
            int status,
            Optional<String> headerRequestId,
            Optional<String> contentType,
            byte[] body,
            String generatedRequestId) {
        if (body.length == 0 || !isJson(contentType)) {
            String requestId = headerRequestId
                    .map(value -> validResponseRequestId(value, generatedRequestId))
                    .orElse(generatedRequestId);
            String detail = body.length == 0
                    ? "without a Light OSS error body"
                    : "with a non-JSON error body";
            return new LightOssApiException(
                    status,
                    "sdk_http_error",
                    "HTTP " + status + " returned " + detail,
                    requestId);
        }
        JsonNode root = json.read(body, headerRequestId.orElse(generatedRequestId));
        if (!root.isObject()) {
            throw new LightOssProtocolException("error response envelope is not an object", generatedRequestId);
        }
        String envelopeRequestId = requiredEnvelopeRequestId(root, generatedRequestId);
        verifyRequestIds(headerRequestId, envelopeRequestId, generatedRequestId);
        JsonNode error = root.get("error");
        if (error == null || !error.isObject()) {
            throw new LightOssProtocolException("error response envelope does not contain error", envelopeRequestId);
        }
        String code = json.requiredText(error, "code", envelopeRequestId);
        String message = json.requiredText(error, "message", envelopeRequestId);
        return new LightOssApiException(status, code, message, envelopeRequestId);
    }

    private static boolean isJson(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return false;
        }
        String mediaType = contentType.get();
        int parameter = mediaType.indexOf(';');
        if (parameter >= 0) {
            mediaType = mediaType.substring(0, parameter);
        }
        mediaType = mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    private String requiredEnvelopeRequestId(JsonNode root, String requestId) {
        return validResponseRequestId(json.requiredText(root, "request_id", requestId), requestId);
    }

    private String requiredHeaderRequestId(Optional<String> header, String requestId) {
        if (header.isEmpty()) {
            throw new LightOssProtocolException("response does not contain X-Request-ID", requestId);
        }
        return validResponseRequestId(header.get(), requestId);
    }

    private String validResponseRequestId(String value, String requestId) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new LightOssProtocolException("response request ID is invalid", requestId);
        }
        return value;
    }

    private void verifyRequestIds(Optional<String> header, String envelope, String requestId) {
        if (header.isPresent() && !header.get().equals(envelope)) {
            throw new LightOssProtocolException("response request IDs do not match", requestId);
        }
    }

    private ContentMetadata contentMetadata(HttpResponse<?> response, String requestId) {
        OptionalLong contentLength = OptionalLong.empty();
        Optional<String> lengthHeader = response.headers().firstValue("Content-Length");
        if (lengthHeader.isPresent()) {
            try {
                long value = Long.parseLong(lengthHeader.get());
                if (value < 0) {
                    throw new NumberFormatException("negative content length");
                }
                contentLength = OptionalLong.of(value);
            } catch (NumberFormatException exception) {
                throw new LightOssProtocolException("response Content-Length is invalid", requestId, exception);
            }
        }
        try {
            return new ContentMetadata(
                    contentLength,
                    response.headers().firstValue("Content-Type"),
                    response.headers().firstValue("ETag"),
                    response.headers().firstValue("X-Object-Visibility").map(Visibility::new),
                    response.headers().firstValue("X-Original-Filename").map(Uris::decodePercent),
                    response.headers().firstValue("Content-Disposition"));
        } catch (RuntimeException exception) {
            throw new LightOssProtocolException("response content metadata is invalid", requestId, exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownsHttpClient) {
            httpClient.close();
        }
    }
}
