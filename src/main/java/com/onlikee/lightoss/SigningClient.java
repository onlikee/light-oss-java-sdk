package com.onlikee.lightoss;

import com.onlikee.lightoss.internal.Checks;
import com.onlikee.lightoss.internal.ClientContext;
import com.onlikee.lightoss.internal.Uris;
import com.onlikee.lightoss.model.SignedDownload;
import com.onlikee.lightoss.model.SignedUpload;
import com.onlikee.lightoss.model.Visibility;
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

    /** Generates a signed authorization for one streamed object upload. */
    public LightOssResponse<SignedUpload> signUpload(SignUploadRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = context.json().object(
                "bucket", request.bucket(),
                "object_key", request.objectKey(),
                "max_size_bytes", request.maxSizeBytes(),
                "visibility", request.visibility().value(),
                "allow_overwrite", request.allowOverwrite(),
                "original_filename", request.originalFilename(),
                "content_type", request.contentType(),
                "expires_in_seconds", request.expiresIn() == null ? null : request.expiresIn().toSeconds());
        return context.json(
                "POST",
                Uris.endpoint(context.baseUri(), "/api/v1/sign/upload"),
                ClientContext.AuthMode.REQUIRED,
                context.jsonBody(body),
                "application/json",
                Map.of(),
                200,
                (data, requestId) -> {
                    var headers = context.json().requiredObject(data, "headers", requestId);
                    return new SignedUpload(
                            context.json().requiredText(data, "method", requestId),
                            URI.create(context.json().requiredText(data, "path", requestId)),
                            Map.of(
                                    "Content-Type", context.json().requiredText(headers, "Content-Type", requestId),
                                    "X-Allow-Overwrite", context.json().requiredText(headers, "X-Allow-Overwrite", requestId),
                                    "X-Object-Visibility", context.json().requiredText(headers, "X-Object-Visibility", requestId),
                                    "X-Original-Filename", context.json().requiredText(headers, "X-Original-Filename", requestId)),
                            Instant.ofEpochSecond(context.json().requiredLong(data, "expires_at", requestId)));
                });
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
            bucket = Checks.rawText(bucket, "bucket");
            objectKey = Checks.rawText(objectKey, "objectKey");
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

    /**
     * Request for a temporary single-object upload authorization.
     *
     * @param bucket bucket name
     * @param objectKey object key
     * @param maxSizeBytes maximum accepted request body size
     * @param visibility object visibility
     * @param allowOverwrite whether the upload may replace an existing object
     * @param originalFilename optional original filename metadata
     * @param contentType optional content type metadata
     * @param expiresIn optional authorization lifetime
     */
    public record SignUploadRequest(
            String bucket,
            String objectKey,
            long maxSizeBytes,
            Visibility visibility,
            boolean allowOverwrite,
            String originalFilename,
            String contentType,
            Duration expiresIn) {
        /** Creates a validated request. */
        public SignUploadRequest {
            bucket = Checks.rawText(bucket, "bucket");
            objectKey = Checks.rawText(objectKey, "objectKey");
            Uris.objectKey(objectKey);
            maxSizeBytes = Checks.positive(maxSizeBytes, "maxSizeBytes");
            visibility = visibility == null ? Visibility.PRIVATE : visibility;
            if (!visibility.isRequestValue()) {
                throw new com.onlikee.lightoss.exception.LightOssValidationException("visibility must be public or private");
            }
            if (originalFilename != null) {
                originalFilename = Checks.text(originalFilename, "originalFilename");
            }
            if (contentType != null) {
                contentType = Checks.text(contentType, "contentType");
            }
            if (expiresIn != null && (expiresIn.isZero() || expiresIn.isNegative() || expiresIn.toSeconds() == 0)) {
                throw new com.onlikee.lightoss.exception.LightOssValidationException("expiresIn must be at least one second");
            }
        }

        /** Creates a builder using private visibility and backend metadata defaults. */
        public static Builder builder(String bucket, String objectKey, long maxSizeBytes) {
            return new Builder(bucket, objectKey, maxSizeBytes);
        }

        /** Builder for {@link SignUploadRequest}. */
        public static final class Builder {
            private final String bucket;
            private final String objectKey;
            private final long maxSizeBytes;
            private Visibility visibility = Visibility.PRIVATE;
            private boolean allowOverwrite;
            private String originalFilename;
            private String contentType;
            private Duration expiresIn;

            private Builder(String bucket, String objectKey, long maxSizeBytes) {
                this.bucket = bucket;
                this.objectKey = objectKey;
                this.maxSizeBytes = maxSizeBytes;
            }

            /** Sets the object visibility bound to the authorization. */
            public Builder visibility(Visibility value) {
                this.visibility = value;
                return this;
            }

            /** Sets whether the authorization permits replacing an existing object. */
            public Builder allowOverwrite(boolean value) {
                this.allowOverwrite = value;
                return this;
            }

            /** Sets the original filename metadata bound to the authorization. */
            public Builder originalFilename(String value) {
                this.originalFilename = value;
                return this;
            }

            /** Sets the content type bound to the authorization. */
            public Builder contentType(String value) {
                this.contentType = value;
                return this;
            }

            /** Sets an explicit authorization lifetime. */
            public Builder expiresIn(Duration value) {
                this.expiresIn = value;
                return this;
            }

            /** Builds the request. */
            public SignUploadRequest build() {
                return new SignUploadRequest(bucket, objectKey, maxSizeBytes, visibility, allowOverwrite,
                        originalFilename, contentType, expiresIn);
            }
        }
    }
}
