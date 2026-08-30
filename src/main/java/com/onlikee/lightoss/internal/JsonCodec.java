package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.exception.LightOssProtocolException;
import com.onlikee.lightoss.exception.LightOssValidationException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new LightOssValidationException("failed to serialize request body", exception);
        }
    }

    public JsonNode read(byte[] value, String requestId) {
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new LightOssProtocolException("response body is not valid JSON", requestId, exception);
        }
    }

    public String requiredText(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isString()) {
            throw type(field, "string", requestId);
        }
        return value.stringValue();
    }

    public String optionalText(JsonNode node, String field, String defaultValue, String requestId) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isString()) {
            throw type(field, "string", requestId);
        }
        return value.stringValue();
    }

    public long requiredLong(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isIntegralNumber()) {
            throw type(field, "integer", requestId);
        }
        return value.longValue();
    }

    public int requiredInt(JsonNode node, String field, String requestId) {
        long value = requiredLong(node, field, requestId);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new LightOssProtocolException("response field '" + field + "' is outside the Java int range", requestId);
        }
        return (int) value;
    }

    public double requiredDouble(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isNumber()) {
            throw type(field, "number", requestId);
        }
        return value.doubleValue();
    }

    public boolean requiredBoolean(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isBoolean()) {
            throw type(field, "boolean", requestId);
        }
        return value.booleanValue();
    }

    public Instant requiredInstant(JsonNode node, String field, String requestId) {
        String value = requiredText(node, field, requestId);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new LightOssProtocolException("response field '" + field + "' is not an ISO-8601 instant", requestId, exception);
        }
    }

    public JsonNode requiredObject(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isObject()) {
            throw type(field, "object", requestId);
        }
        return value;
    }

    public JsonNode requiredArray(JsonNode node, String field, String requestId) {
        JsonNode value = required(node, field, requestId);
        if (!value.isArray()) {
            throw type(field, "array", requestId);
        }
        return value;
    }

    public List<String> requiredStrings(JsonNode node, String field, String requestId) {
        JsonNode values = requiredArray(node, field, requestId);
        List<String> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isString()) {
                throw type(field + "[]", "string", requestId);
            }
            result.add(value.stringValue());
        }
        return List.copyOf(result);
    }

    public Map<String, Object> object(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("object values must contain key-value pairs");
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put((String) values[index], values[index + 1]);
            }
        }
        return result;
    }

    private JsonNode required(JsonNode node, String field, String requestId) {
        if (node == null || !node.isObject()) {
            throw new LightOssProtocolException("response data is not an object", requestId);
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LightOssProtocolException("response field '" + field + "' is missing", requestId);
        }
        return value;
    }

    private LightOssProtocolException type(String field, String type, String requestId) {
        return new LightOssProtocolException("response field '" + field + "' is not a " + type, requestId);
    }
}
