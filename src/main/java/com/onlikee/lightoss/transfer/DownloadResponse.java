package com.onlikee.lightoss.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import com.onlikee.lightoss.model.Visibility;

/**
 * A streaming successful response. The caller must close this response.
 */
public final class DownloadResponse implements AutoCloseable {
    private final InputStream body;
    private final ContentMetadata metadata;
    private final String requestId;

    /** Creates a streaming response. */
    public DownloadResponse(InputStream body, ContentMetadata metadata, String requestId) {
        this.body = Objects.requireNonNull(body, "body");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    /** Returns the response body stream. */
    public InputStream body() {
        return body;
    }

    /** Returns response content metadata. */
    public ContentMetadata metadata() {
        return metadata;
    }

    /** Returns the declared content length, if present. */
    public OptionalLong contentLength() { return metadata.contentLength(); }

    /** Returns the media type, if present. */
    public Optional<String> contentType() { return metadata.contentType(); }

    /** Returns the entity tag, if present. */
    public Optional<String> etag() { return metadata.etag(); }

    /** Returns object visibility, if present. */
    public Optional<Visibility> visibility() { return metadata.visibility(); }

    /** Returns the decoded original filename, if present. */
    public Optional<String> originalFilename() { return metadata.originalFilename(); }

    /** Returns the Content-Disposition value, if present. */
    public Optional<String> contentDisposition() { return metadata.contentDisposition(); }

    /** Returns the Light OSS request identifier. */
    public String requestId() {
        return requestId;
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
