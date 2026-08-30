package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.transfer.UploadSource;
import java.io.FileNotFoundException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MultipartBody {
    private final String boundary;
    private final List<HttpRequest.BodyPublisher> publishers = new ArrayList<>();

    public MultipartBody() {
        boundary = "light-oss-" + UUID.randomUUID().toString().replace("-", "");
    }

    public MultipartBody text(String name, String value) {
        return text(name, value, "text/plain; charset=utf-8");
    }

    public MultipartBody text(String name, String value, String contentType) {
        publishers.add(bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + token(name) + "\"\r\n"
                + "Content-Type: " + Checks.headerValue(contentType, "contentType") + "\r\n\r\n"
                + value + "\r\n"));
        return this;
    }

    public MultipartBody file(String fieldName, UploadSource source) {
        String encodedFilename = Uris.encodeHeaderFilename(source.filename());
        String disposition = "Content-Disposition: form-data; name=\"" + token(fieldName)
                + "\"; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename + "\r\n";
        publishers.add(bytes("--" + boundary + "\r\n"
                + disposition
                + "Content-Type: " + Checks.headerValue(source.contentType(), "contentType") + "\r\n\r\n"));
        try {
            publishers.add(SourceBodyPublishers.publisher(source));
        } catch (FileNotFoundException exception) {
            throw new LightOssValidationException("upload path is not readable", exception);
        }
        publishers.add(bytes("\r\n"));
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        List<HttpRequest.BodyPublisher> complete = new ArrayList<>(publishers);
        complete.add(bytes("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.concat(complete.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private static HttpRequest.BodyPublisher bytes(String value) {
        return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String value) {
        String checked = Checks.headerValue(value, "multipart field name");
        if (checked.indexOf('"') >= 0 || checked.indexOf(';') >= 0) {
            throw new LightOssValidationException("multipart field name contains unsupported characters");
        }
        return checked;
    }
}
