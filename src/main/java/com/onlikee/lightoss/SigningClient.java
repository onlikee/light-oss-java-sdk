package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.SignedDownload;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Signed-download operations. */
public final class SigningClient {
    private final ClientContext context;

    SigningClient(ClientContext context) {
        this.context = context;
    }

    /** Generates a signed relative download path. */
    public LightOssResponse<SignedDownload> signDownload(SignDownloadRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = context.json().object(
                "bucket", request.bucket(),
                "object_key", request.objectKey(),
                "expires_in_seconds", request.expiresIn() == null ? null : request.expiresIn().toSeconds());
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), "/api/v1/sign/download"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(body),
                "application/json",
                Map.of(),
                200,
                (data, requestId) -> new SignedDownload(
                        URI.create(context.json().requiredText(data, "path", requestId)),
                        Instant.ofEpochSecond(context.json().requiredLong(data, "expires_at", requestId))));
    }

    /**
     * Request for a signed object-download path.
     *
     * @param bucket bucket name
     * @param objectKey object key
     * @param expiresIn optional signature lifetime
     */
    public record SignDownloadRequest(String bucket, String objectKey, Duration expiresIn) {
        public SignDownloadRequest {
            bucket = Checks.text(bucket, "bucket");
            objectKey = Checks.text(objectKey, "objectKey");
            Uris.objectKey(objectKey);
            if (expiresIn != null) {
                expiresIn = Checks.positive(expiresIn, "expiresIn");
                if (expiresIn.toSeconds() == 0) {
                    throw new com.onlikee.lightoss.exception.LightOssValidationException("expiresIn must be at least one second");
                }
            }
        }

        /** Creates a request that uses the backend default expiry. */
        public static SignDownloadRequest of(String bucket, String objectKey) {
            return new SignDownloadRequest(bucket, objectKey, null);
        }

        /** Creates a request with an explicit expiry duration. */
        public static SignDownloadRequest of(String bucket, String objectKey, Duration expiresIn) {
            return new SignDownloadRequest(bucket, objectKey, expiresIn);
        }
    }
}
