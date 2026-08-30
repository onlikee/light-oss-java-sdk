package com.onlikee.lightoss.transfer;

import com.onlikee.lightoss.exception.LightOssValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * A repeatable source for streamed uploads.
 *
 * <p>Input-stream suppliers must return a new stream on every invocation. Streams opened by
 * this source are closed by the SDK.</p>
 */
public sealed interface UploadSource permits UploadSource.PathSource, UploadSource.BytesSource, UploadSource.StreamSource {
    /** Returns the filename sent in multipart requests and used as the default original filename. */
    String filename();

    /** Returns the media type sent for this source. */
    String contentType();

    /** Returns the content length when known. */
    OptionalLong contentLength();

    /** Opens a new readable stream. */
    InputStream openStream() throws IOException;

    /** Creates a source backed by a filesystem path with an octet-stream media type. */
    static UploadSource fromPath(Path path) {
        return fromPath(path, "application/octet-stream");
    }

    /** Creates a source backed by a filesystem path. */
    static UploadSource fromPath(Path path, String contentType) {
        return new PathSource(path, contentType);
    }

    /** Creates an in-memory source. The supplied bytes are defensively copied. */
    static UploadSource fromBytes(String filename, String contentType, byte[] bytes) {
        return new BytesSource(filename, contentType, bytes);
    }

    /** Creates a source with an unknown content length. */
    static UploadSource fromInputStream(
            String filename,
            String contentType,
            Supplier<? extends InputStream> streamSupplier) {
        return new StreamSource(filename, contentType, OptionalLong.empty(), streamSupplier);
    }

    /** Creates a source with a declared content length. */
    static UploadSource fromInputStream(
            String filename,
            String contentType,
            long contentLength,
            Supplier<? extends InputStream> streamSupplier) {
        if (contentLength < 0) {
            throw new LightOssValidationException("contentLength must not be negative");
        }
        return new StreamSource(filename, contentType, OptionalLong.of(contentLength), streamSupplier);
    }

    /**
     * Path-backed upload source.
     *
     * @param path readable file path
     * @param contentType media type
     */
    record PathSource(Path path, String contentType) implements UploadSource {
        /** Creates a path-backed source. */
        public PathSource {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            contentType = requireText(contentType, "contentType");
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new LightOssValidationException("path must be a readable regular file: " + path);
            }
        }

        @Override
        public String filename() {
            return path.getFileName().toString();
        }

        @Override
        public OptionalLong contentLength() {
            try {
                return OptionalLong.of(Files.size(path));
            } catch (IOException exception) {
                throw new LightOssValidationException("failed to inspect upload path: " + path, exception);
            }
        }

        @Override
        public InputStream openStream() throws IOException {
            return Files.newInputStream(path);
        }
    }

    /**
     * Byte-array-backed upload source.
     *
     * @param filename source filename
     * @param contentType media type
     * @param bytes content bytes
     */
    record BytesSource(String filename, String contentType, byte[] bytes) implements UploadSource {
        /** Creates a byte-array-backed source. */
        public BytesSource {
            filename = requireText(filename, "filename");
            contentType = requireText(contentType, "contentType");
            bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public OptionalLong contentLength() {
            return OptionalLong.of(bytes.length);
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    /**
     * Supplier-backed upload source.
     *
     * @param filename source filename
     * @param contentType media type
     * @param contentLength declared length when known
     * @param streamSupplier repeatable stream supplier
     */
    record StreamSource(
            String filename,
            String contentType,
            OptionalLong contentLength,
            Supplier<? extends InputStream> streamSupplier) implements UploadSource {
        /** Creates a supplier-backed source. */
        public StreamSource {
            filename = requireText(filename, "filename");
            contentType = requireText(contentType, "contentType");
            contentLength = contentLength == null ? OptionalLong.empty() : contentLength;
            if (contentLength.isPresent() && contentLength.getAsLong() < 0) {
                throw new LightOssValidationException("contentLength must not be negative");
            }
            streamSupplier = Objects.requireNonNull(streamSupplier, "streamSupplier");
        }

        @Override
        public InputStream openStream() {
            InputStream stream = streamSupplier.get();
            if (stream == null) {
                throw new LightOssValidationException("streamSupplier returned null");
            }
            return stream;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new LightOssValidationException(name + " must not be blank");
        }
        return trimmed;
    }
}
