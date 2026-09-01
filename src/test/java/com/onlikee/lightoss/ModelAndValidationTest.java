package com.onlikee.lightoss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onlikee.lightoss.exception.LightOssConfigurationException;
import com.onlikee.lightoss.exception.LightOssValidationException;
import com.onlikee.lightoss.model.EntryType;
import com.onlikee.lightoss.model.ExplorerEntry;
import com.onlikee.lightoss.model.Page;
import com.onlikee.lightoss.model.RateLimitBackend;
import com.onlikee.lightoss.model.SignedDownload;
import com.onlikee.lightoss.model.StorageLimitStatus;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.UploadSource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelAndValidationTest {
    @Test
    void openValuesPreserveUnknownWireValues() {
        assertEquals("future", new Visibility("FUTURE").value());
        assertEquals("archive", new EntryType("ARCHIVE").value());
        assertEquals("remote", new RateLimitBackend("REMOTE").value());
        assertEquals("warning", new StorageLimitStatus("WARNING").value());
        assertTrue(Visibility.PUBLIC.isRequestValue());
        assertTrue(!new Visibility("future").isRequestValue());
    }

    @Test
    void explorerEntryIsSealedAndHasKnownAndUnknownVariants() {
        ExplorerEntry directory = new ExplorerEntry.DirectoryEntry("docs/", "docs", true);
        ExplorerEntry unknown = new ExplorerEntry.UnknownEntry(new EntryType("symlink"), "link", "link");
        assertInstanceOf(ExplorerEntry.DirectoryEntry.class, directory);
        assertInstanceOf(ExplorerEntry.UnknownEntry.class, unknown);
        assertTrue(ExplorerEntry.class.isSealed());
    }

    @Test
    void collectionsAndBytesAreDefensivelyCopied() throws Exception {
        List<String> mutable = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(mutable, Optional.of("next"));
        mutable.add("b");
        assertEquals(List.of("a"), page.items());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("c"));

        byte[] bytes = {1, 2};
        UploadSource.BytesSource source = (UploadSource.BytesSource) UploadSource.fromBytes("a.bin", "application/octet-stream", bytes);
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, source.openStream().readAllBytes());
        byte[] exposed = source.bytes();
        exposed[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, source.openStream().readAllBytes());
    }

    @Test
    void supplierSourceIsRepeatableAndValidatesLength() throws Exception {
        UploadSource source = UploadSource.fromInputStream(
                "a.txt", "text/plain", 1, () -> new ByteArrayInputStream(new byte[] {7}));
        assertEquals(7, source.openStream().read());
        assertEquals(7, source.openStream().read());
        assertThrows(LightOssValidationException.class,
                () -> UploadSource.fromInputStream("a", "text/plain", -1, () -> new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void requestBuildersEnforceStableLocalContracts() {
        assertThrows(LightOssValidationException.class,
                () -> ObjectClient.ListObjectsRequest.builder("demo").limit(101).build());
        assertThrows(LightOssValidationException.class,
                () -> ExplorerClient.ListEntriesRequest.builder("demo").limit(201).build());
        assertThrows(LightOssValidationException.class,
                () -> new ExplorerClient.DeleteItem(new EntryType("future"), "x"));
        assertThrows(LightOssValidationException.class,
                () -> ObjectClient.UploadObjectRequest.builder(
                        "demo", "a", UploadSource.fromBytes("a", "text/plain", new byte[0]))
                        .visibility(new Visibility("future")).build());
        assertThrows(LightOssValidationException.class,
                () -> new ObjectClient.UploadItem("../escape.txt",
                        UploadSource.fromBytes("escape.txt", "text/plain", new byte[0])));

        ObjectClient.UploadItem item = new ObjectClient.UploadItem(
                "a.txt", UploadSource.fromBytes("a.txt", "text/plain", new byte[0]));
        assertThrows(LightOssValidationException.class,
                () -> ObjectClient.UploadBatchRequest.builder("demo", List.of(item, item)).build());
        assertThrows(LightOssValidationException.class,
                () -> new SiteClient.PublishObjectRequest("demo", "a", true, "", true, List.of()));
    }

    @Test
    void requestDefaultsAndRawValuesMatchBackendSemantics() {
        ObjectClient.ListObjectsRequest objects = ObjectClient.ListObjectsRequest.builder("demo").build();
        assertEquals(20, objects.limit());
        RecycleBinClient.ListRequest recycle = RecycleBinClient.ListRequest.builder().build();
        assertEquals(20, recycle.limit());
        ExplorerClient.ListEntriesRequest explorer = ExplorerClient.ListEntriesRequest.builder("demo").build();
        assertEquals(ExplorerClient.SortBy.CREATED_AT, explorer.sortBy());
        assertEquals(ExplorerClient.SortOrder.DESC, explorer.sortOrder());

        ObjectClient.ListObjectsRequest raw = new ObjectClient.ListObjectsRequest(
                " demo ", " docs/ ", 20, " cursor ");
        assertEquals(" demo ", raw.bucket());
        assertEquals(" docs/ ", raw.prefix());
        assertEquals(" cursor ", raw.cursor());
        assertEquals(" key ", ObjectClient.DownloadObjectRequest.builder("demo", " key ").build().key());

        assertEquals("", SiteClient.SiteRequest.builder("demo").indexDocument("").build().indexDocument());
        ObjectClient.UploadItem item = new ObjectClient.UploadItem(
                "index.html", UploadSource.fromBytes("index.html", "text/html", new byte[0]));
        assertEquals("", SiteClient.PublishRequest.builder("demo", List.of("demo.localhost"), List.of(item))
                .indexDocument("").build().indexDocument());
    }

    @Test
    void clientBuilderEnforcesOriginAndMutualExclusion() {
        assertThrows(LightOssValidationException.class,
                () -> LightOssClient.builder(URI.create("https://example.com/api")));
        assertThrows(LightOssConfigurationException.class,
                () -> LightOssClient.builder(URI.create("https://example.com"))
                        .bearerToken("a").tokenProvider(() -> "b"));
    }

    @Test
    void signedDownloadRequiresRelativeApiPath() {
        SignedDownload value = new SignedDownload(
                URI.create("/api/v1/buckets/demo/objects/a?expires=1"), Instant.EPOCH);
        assertEquals("/api/v1/buckets/demo/objects/a?expires=1", value.path().toString());
        assertThrows(IllegalArgumentException.class,
                () -> new SignedDownload(URI.create("https://example.com/a"), Instant.EPOCH));
    }
}
