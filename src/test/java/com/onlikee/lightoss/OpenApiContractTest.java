package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OpenApiContractTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete", "head");

    @Test
    void snapshotOrConfiguredOpenApiMatchesAllOperationsSecurityAndKeySchemas() throws Exception {
        JsonNode document = loadDocument();
        if (document.has("operations")) {
            validateCompactSnapshot(document);
        } else {
            validateOpenApi(document);
        }
    }

    private static JsonNode loadDocument() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String configured = System.getProperty("lightoss.openapi.file");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(path), "configured OpenAPI file does not exist: " + path);
            try (InputStream input = Files.newInputStream(path)) {
                return mapper.readTree(input);
            }
        }
        try (InputStream input = OpenApiContractTest.class.getResourceAsStream("/openapi-contract-snapshot.json")) {
            assertNotNull(input, "embedded OpenAPI contract snapshot is missing");
            return mapper.readTree(input);
        }
    }

    private static void validateCompactSnapshot(JsonNode snapshot) {
        Map<String, ExpectedOperation> expected = expectedOperations();
        JsonNode operations = snapshot.get("operations");
        assertEquals(38, operations.size());
        Set<String> seen = new HashSet<>();
        for (JsonNode operation : operations) {
            String method = operation.get(0).stringValue();
            String path = operation.get(1).stringValue();
            String key = method + " " + path;
            ExpectedOperation value = expected.get(key);
            assertNotNull(value, "unexpected snapshot operation: " + key);
            assertEquals(value.operationId(), operation.get(2).stringValue(), key);
            assertEquals(value.security(), operation.get(3).stringValue(), key);
            assertTrue(seen.add(key), "duplicate snapshot operation: " + key);
        }
        assertEquals(expected.keySet(), seen);

        JsonNode contracts = snapshot.get("contracts");
        assertEquals(1, contracts.get("uploadManifestMin").intValue());
        assertEquals(2000, contracts.get("uploadManifestMax").intValue());
        assertEquals(1, contracts.get("siteManifestMin").intValue());
        assertEquals(2000, contracts.get("siteManifestMax").intValue());
        assertEquals(200, contracts.get("explorerBatchMax").intValue());
        assertEquals(200, contracts.get("recycleBatchMax").intValue());
        assertEquals(Set.of("directory", "file"), strings(contracts.get("explorerEntryTypes")));
        assertEquals(20, contracts.get("objectListDefault").intValue());
        assertEquals(20, contracts.get("recycleListDefault").intValue());
        assertEquals("created_at", contracts.get("explorerSortByDefault").stringValue());
        assertEquals("desc", contracts.get("explorerSortOrderDefault").stringValue());
        assertEquals("*/*", contracts.get("directUploadMediaType").stringValue());
        assertTrue(contracts.get("folderArchiveContentDisposition").booleanValue());
        assertFalse(contracts.get("headResponsesHaveBodies").booleanValue());
        assertFalse(contracts.get("siteInternalErrorHasBody").booleanValue());
    }

    private static void validateOpenApi(JsonNode document) {
        Map<String, ExpectedOperation> expected = expectedOperations();
        ObjectNode paths = (ObjectNode) document.get("paths");
        Set<String> actualKeys = new HashSet<>();
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            ObjectNode pathItem = (ObjectNode) pathEntry.getValue();
            for (Map.Entry<String, JsonNode> methodEntry : pathItem.properties()) {
                if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                    continue;
                }
                String key = methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
                actualKeys.add(key);
                ExpectedOperation expectedOperation = expected.get(key);
                assertNotNull(expectedOperation, "unexpected OpenAPI operation: " + key);
                JsonNode operation = methodEntry.getValue();
                assertEquals(expectedOperation.operationId(), operation.get("operationId").stringValue(), key);
                assertEquals(expectedOperation.security(), security(operation), key);
            }
        }
        assertEquals(expected.keySet(), actualKeys);

        JsonNode schemas = document.get("components").get("schemas");
        assertArrayBounds(schemas.get("UploadBatchForm").get("properties").get("manifest"), 1, 2000);
        assertArrayBounds(schemas.get("PublishSiteForm").get("properties").get("manifest"), 1, 2000);
        assertArrayBounds(schemas.get("RecycleBinBatchRequest").get("properties").get("item_ids"), 1, 200);
        JsonNode explorerItems = document.get("paths")
                .get("/api/v1/buckets/{bucket}/entries/batch-delete").get("post")
                .get("requestBody").get("content").get("application/json").get("schema")
                .get("properties").get("items");
        assertArrayBounds(explorerItems, 1, 200);
        assertEquals(Set.of("directory", "file"),
                strings(schemas.get("ExplorerEntry").get("properties").get("type").get("enum")));

        JsonNode parameters = document.get("components").get("parameters");
        assertEquals(20, parameters.get("ObjectListLimitQuery").get("schema").get("default").intValue());
        assertEquals("created_at", parameters.get("ExplorerSortByQuery").get("schema").get("default").stringValue());
        assertEquals("desc", parameters.get("ExplorerSortOrderQuery").get("schema").get("default").stringValue());

        JsonNode objectPath = paths.get("/api/v1/buckets/{bucket}/objects/{key}");
        JsonNode uploadContent = objectPath.get("put").get("requestBody").get("content");
        assertEquals(Set.of("*/*"), propertyNames(uploadContent));

        JsonNode archiveSuccess = paths.get("/api/v1/buckets/{bucket}/folders/archive")
                .get("get").get("responses").get("200");
        assertTrue(archiveSuccess.get("headers").has("Content-Disposition"));

        assertHeadHasNoResponseBodies(objectPath.get("head"));
        assertHeadHasNoResponseBodies(paths.get("/sites/{siteID}").get("head"));
        assertHeadHasNoResponseBodies(paths.get("/sites/{siteID}/{path}").get("head"));
        assertSiteErrorRepresentations(paths.get("/sites/{siteID}").get("get"));
        assertSiteErrorRepresentations(paths.get("/sites/{siteID}/{path}").get("get"));
    }

    private static void assertHeadHasNoResponseBodies(JsonNode operation) {
        for (JsonNode response : operation.get("responses")) {
            assertFalse(response.has("content"), operation.get("operationId").stringValue());
        }
    }

    private static void assertSiteErrorRepresentations(JsonNode operation) {
        JsonNode responses = operation.get("responses");
        assertFalse(responses.get("500").has("content"), operation.get("operationId").stringValue());
        JsonNode unavailable = responses.get("503");
        assertTrue(unavailable.get("description").stringValue().contains("status-only"));
        assertTrue(unavailable.get("content").has("application/json"));
    }

    private static void assertArrayBounds(JsonNode node, int min, int max) {
        assertEquals(min, node.get("minItems").intValue());
        assertEquals(max, node.get("maxItems").intValue());
    }

    private static String security(JsonNode operation) {
        JsonNode security = operation.get("security");
        if (security == null || security.isEmpty()) {
            return "none";
        }
        for (JsonNode requirement : security) {
            if (requirement.has("bearerAuth")) {
                return "bearer";
            }
        }
        return "other";
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> result = new HashSet<>();
        for (JsonNode item : array) {
            result.add(item.stringValue());
        }
        return result;
    }

    private static Set<String> propertyNames(JsonNode object) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, JsonNode> property : ((ObjectNode) object).properties()) {
            result.add(property.getKey());
        }
        return result;
    }

    private static Map<String, ExpectedOperation> expectedOperations() {
        Map<String, ExpectedOperation> expected = new HashMap<>();
        add(expected, "GET", "/livez", "getLiveness", "none");
        add(expected, "GET", "/readyz", "getReadiness", "none");
        add(expected, "GET", "/sites/{siteID}", "downloadSiteRoot", "none");
        add(expected, "HEAD", "/sites/{siteID}", "headSiteRoot", "none");
        add(expected, "GET", "/sites/{siteID}/{path}", "downloadSitePath", "none");
        add(expected, "HEAD", "/sites/{siteID}/{path}", "headSitePath", "none");
        add(expected, "GET", "/api/v1/healthz", "getProtectedHealthz", "bearer");
        add(expected, "GET", "/api/v1/system/metrics", "getSystemMetrics", "bearer");
        add(expected, "GET", "/api/v1/system/stats", "getSystemStats", "bearer");
        add(expected, "PUT", "/api/v1/system/storage/quota", "updateSystemStorageQuota", "bearer");
        add(expected, "GET", "/api/v1/buckets", "listBuckets", "bearer");
        add(expected, "POST", "/api/v1/buckets", "createBucket", "bearer");
        add(expected, "DELETE", "/api/v1/buckets/{bucket}", "deleteBucket", "bearer");
        add(expected, "GET", "/api/v1/buckets/{bucket}/folders", "listFolders", "bearer");
        add(expected, "POST", "/api/v1/buckets/{bucket}/folders", "createFolder", "bearer");
        add(expected, "DELETE", "/api/v1/buckets/{bucket}/folders", "deleteFolder", "bearer");
        add(expected, "GET", "/api/v1/buckets/{bucket}/folders/archive", "downloadFolderArchive", "bearer");
        add(expected, "GET", "/api/v1/buckets/{bucket}/entries", "listExplorerEntries", "bearer");
        add(expected, "POST", "/api/v1/buckets/{bucket}/entries/batch-delete", "deleteExplorerEntriesBatch", "bearer");
        add(expected, "GET", "/api/v1/buckets/{bucket}/objects", "listObjects", "bearer");
        add(expected, "POST", "/api/v1/buckets/{bucket}/objects/batch", "uploadObjectBatch", "bearer");
        add(expected, "GET", "/api/v1/buckets/{bucket}/objects/{key}", "downloadObject", "none");
        add(expected, "HEAD", "/api/v1/buckets/{bucket}/objects/{key}", "headObject", "none");
        add(expected, "PUT", "/api/v1/buckets/{bucket}/objects/{key}", "uploadObject", "bearer");
        add(expected, "DELETE", "/api/v1/buckets/{bucket}/objects/{key}", "deleteObject", "bearer");
        add(expected, "PATCH", "/api/v1/buckets/{bucket}/objects/visibility/{key}", "updateObjectVisibility", "bearer");
        add(expected, "GET", "/api/v1/recycle-bin/objects", "listRecycleBinObjects", "bearer");
        add(expected, "POST", "/api/v1/recycle-bin/objects/restore", "restoreRecycleBinObjects", "bearer");
        add(expected, "POST", "/api/v1/recycle-bin/objects/batch-delete", "deleteRecycleBinObjects", "bearer");
        add(expected, "GET", "/api/v1/sites", "listSites", "bearer");
        add(expected, "POST", "/api/v1/sites", "createSite", "bearer");
        add(expected, "POST", "/api/v1/sites/publish", "publishSiteUpload", "bearer");
        add(expected, "POST", "/api/v1/sites/publish/object", "publishObjectSite", "bearer");
        add(expected, "POST", "/api/v1/sites/publish/file", "publishSiteFile", "bearer");
        add(expected, "GET", "/api/v1/sites/{siteID}", "getSite", "bearer");
        add(expected, "PUT", "/api/v1/sites/{siteID}", "updateSite", "bearer");
        add(expected, "DELETE", "/api/v1/sites/{siteID}", "deleteSite", "bearer");
        add(expected, "POST", "/api/v1/sign/download", "signDownload", "bearer");
        return Map.copyOf(expected);
    }

    private static void add(
            Map<String, ExpectedOperation> operations,
            String method,
            String path,
            String operationId,
            String security) {
        operations.put(method + " " + path, new ExpectedOperation(operationId, security));
    }

    private record ExpectedOperation(String operationId, String security) {
    }
}
