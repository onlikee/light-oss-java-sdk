package com.onlikee.lightoss.internal;

import com.onlikee.lightoss.model.ObjectInfo;
import com.onlikee.lightoss.model.Page;
import com.onlikee.lightoss.model.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import tools.jackson.databind.JsonNode;

public final class Parsers {
    private Parsers() {
    }

    public static ObjectInfo object(JsonCodec json, JsonNode node, String requestId) {
        return new ObjectInfo(
                json.requiredLong(node, "id", requestId),
                json.requiredText(node, "bucket_name", requestId),
                json.requiredText(node, "object_key", requestId),
                json.requiredText(node, "original_filename", requestId),
                json.requiredLong(node, "size", requestId),
                json.requiredText(node, "content_type", requestId),
                json.requiredText(node, "etag", requestId),
                new Visibility(json.requiredText(node, "visibility", requestId)),
                json.requiredInstant(node, "created_at", requestId),
                json.requiredInstant(node, "updated_at", requestId));
    }

    public static <T> List<T> list(
            JsonCodec json,
            JsonNode data,
            String field,
            String requestId,
            BiFunction<JsonNode, String, T> parser) {
        JsonNode items = json.requiredArray(data, field, requestId);
        List<T> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            result.add(parser.apply(item, requestId));
        }
        return List.copyOf(result);
    }

    public static <T> Page<T> page(
            JsonCodec json,
            JsonNode data,
            String requestId,
            BiFunction<JsonNode, String, T> parser) {
        List<T> items = list(json, data, "items", requestId, parser);
        String nextCursor = json.requiredText(data, "next_cursor", requestId);
        return new Page<>(items, nextCursor.isBlank() ? Optional.empty() : Optional.of(nextCursor));
    }
}
