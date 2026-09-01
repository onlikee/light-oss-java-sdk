package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.exception.LightOssValidationException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class Checks {
    private Checks() {
    }

    public static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new LightOssValidationException(name + " must not be blank");
        }
        return trimmed;
    }

    public static String rawText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new LightOssValidationException(name + " must not be blank");
        }
        return value;
    }

    public static String headerValue(String value, String name) {
        String checked = text(value, name);
        if (checked.indexOf('\r') >= 0 || checked.indexOf('\n') >= 0) {
            throw new LightOssValidationException(name + " must not contain CR or LF");
        }
        return checked;
    }

    public static int range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new LightOssValidationException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    public static long positive(long value, String name) {
        if (value <= 0) {
            throw new LightOssValidationException(name + " must be greater than zero");
        }
        return value;
    }

    public static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new LightOssValidationException(name + " must be greater than zero");
        }
        return value;
    }

    public static <T> List<T> list(List<T> value, int min, int max, String name) {
        Objects.requireNonNull(value, name);
        List<T> copy = List.copyOf(value);
        if (copy.size() < min || copy.size() > max) {
            throw new LightOssValidationException(name + " must contain between " + min + " and " + max + " items");
        }
        return copy;
    }

    public static URI baseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        String scheme = value.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new LightOssValidationException("baseUri must use http or https");
        }
        if (value.getRawAuthority() == null || value.getRawAuthority().isBlank()) {
            throw new LightOssValidationException("baseUri must include a host");
        }
        if (value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new LightOssValidationException("baseUri must not include user-info, query, or fragment");
        }
        String path = value.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new LightOssValidationException("baseUri must be an origin URI without a path prefix");
        }
        return URI.create(scheme.toLowerCase() + "://" + value.getRawAuthority());
    }
}
