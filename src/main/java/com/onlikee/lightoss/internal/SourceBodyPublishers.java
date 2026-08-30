package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.transfer.UploadSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;

public final class SourceBodyPublishers {
    private SourceBodyPublishers() {
    }

    public static HttpRequest.BodyPublisher publisher(UploadSource source) throws FileNotFoundException {
        if (source instanceof UploadSource.PathSource pathSource) {
            return HttpRequest.BodyPublishers.ofFile(pathSource.path());
        }
        if (source instanceof UploadSource.BytesSource bytesSource) {
            return HttpRequest.BodyPublishers.ofByteArray(bytesSource.bytes());
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return source.openStream();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        if (source.contentLength().isPresent()) {
            return HttpRequest.BodyPublishers.fromPublisher(publisher, source.contentLength().getAsLong());
        }
        return publisher;
    }
}
