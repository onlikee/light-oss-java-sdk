package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.exception.LightOssValidationException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Uris {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Uris() {
    }

    public static URI endpoint(URI baseUri, String path) {
        return endpoint(baseUri, path, Map.of());
    }

    public static URI endpoint(URI baseUri, String path, Map<String, ?> query) {
        StringBuilder value = new StringBuilder(baseUri.toString());
        if (!path.startsWith("/")) {
            value.append('/');
        }
        value.append(path);
        boolean first = true;
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String stringValue = entry.getValue().toString();
            if (stringValue.isEmpty()) {
                continue;
            }
            value.append(first ? '?' : '&');
            first = false;
            value.append(encode(entry.getKey())).append('=').append(encode(stringValue));
        }
        return URI.create(value.toString());
    }

    public static Map<String, Object> query() {
        return new LinkedHashMap<>();
    }

    public static String segment(String value) {
        return encode(Checks.text(value, "path segment"));
    }

    public static String objectKey(String value) {
        String key = Checks.text(value, "objectKey");
        if (key.length() > 512 || key.indexOf('\0') >= 0 || key.indexOf('\\') >= 0) {
            throw new LightOssValidationException("objectKey contains unsupported characters or exceeds 512 characters");
        }
        String[] segments = key.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (segments[index].isEmpty() || segments[index].equals(".") || segments[index].equals("..")) {
                throw new LightOssValidationException("objectKey contains an invalid path segment");
            }
            if (index > 0) {
                result.append('/');
            }
            result.append(encode(segments[index]));
        }
        return result.toString();
    }

    public static URI signedPath(URI baseUri, URI signedPath) {
        if (signedPath == null || signedPath.isAbsolute() || signedPath.getRawAuthority() != null || signedPath.getRawFragment() != null) {
            throw new LightOssValidationException("signedPath must be a relative Light OSS path");
        }
        String path = signedPath.getRawPath();
        if (path == null || !path.startsWith("/api/v1/buckets/") || !path.contains("/objects/")) {
            throw new LightOssValidationException("signedPath is not a Light OSS object path");
        }
        return URI.create(baseUri.toString() + signedPath);
    }

    public static URI publicSiteUri(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getRawAuthority() == null) {
            throw new LightOssValidationException("site URI must be absolute");
        }
        String scheme = uri.getScheme();
        if (!(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new LightOssValidationException("site URI must use http or https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new LightOssValidationException("site URI must not contain user-info or fragment");
        }
        return uri;
    }

    public static String encodeHeaderFilename(String value) {
        return encode(Checks.text(value, "filename"));
    }

    public static String decodePercent(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            byte[] encoded = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(encoded);
            index++;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int unsigned = item & 0xFF;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '.'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
            }
        }
        return encoded.toString();
    }
}
