# Light OSS Java SDK

[Chinese documentation](README.zh-CN.md)

The synchronous Java 21 SDK for the Light OSS HTTP API. This library targets the Light OSS
Go/Gin API; it is not an Amazon S3 client. Version 0.1.0 covers all 38 documented OpenAPI
operations plus public custom-domain site GET and HEAD routing.

## Requirements and dependency

- Java 21 or newer
- A Light OSS service origin, such as `https://oss.example.com`

```xml
<dependency>
  <groupId>com.onlikee</groupId>
  <artifactId>light-oss-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

The client is immutable and thread-safe. It uses the JDK HTTP client, does not retry or follow
redirects by default, and never performs automatic pagination.

## Create a client

```java
import com.onlikee.lightoss.LightOssClient;
import java.net.URI;

try (LightOssClient client = LightOssClient.builder(URI.create("https://oss.example.com"))
        .bearerToken(System.getenv("LIGHT_OSS_TOKEN"))
        .build()) {
    var buckets = client.buckets().list().data();
}
```

For rotating credentials, use a thread-safe provider. It is evaluated for every protected call:

```java
LightOssClient client = LightOssClient.builder(URI.create("https://oss.example.com"))
        .tokenProvider(tokenStore::currentToken)
        .build();
```

The default connect timeout is 10 seconds and no total request timeout is set. A custom timeout,
request-ID provider, or caller-owned `HttpClient` can be configured through the builder.

## Upload and download

`UploadSource` supports `Path`, defensive-copy `byte[]`, and repeatable input-stream suppliers.
Path and stream bodies are sent incrementally.

```java
import com.onlikee.lightoss.ObjectClient;
import com.onlikee.lightoss.model.Visibility;
import com.onlikee.lightoss.transfer.UploadSource;
import java.nio.file.Path;

var upload = ObjectClient.UploadObjectRequest.builder(
        "documents",
        "reports/2026.pdf",
        UploadSource.fromPath(Path.of("report.pdf"), "application/pdf"))
    .visibility(Visibility.PRIVATE)
    .allowOverwrite(false)
    .build();

var object = client.objects().upload(upload).data();
```

Downloads and ZIP archives are streaming responses and must be closed:

```java
var request = ObjectClient.DownloadObjectRequest.builder("documents", "reports/2026.pdf")
        .forceDownload(true)
        .build();

try (var download = client.objects().download(request)) {
    download.body().transferTo(outputStream);
    download.etag().ifPresent(System.out::println);
}
```

## Batch operations

Object and site multipart uploads keep the backend's whole-batch transaction semantics. The SDK
generates file field names, boundaries, and the JSON manifest.

```java
var items = List.of(
        new ObjectClient.UploadItem("assets/app.js", UploadSource.fromPath(Path.of("app.js"))),
        new ObjectClient.UploadItem("index.html", UploadSource.fromPath(Path.of("index.html"))));

var result = client.objects().uploadBatch(
        ObjectClient.UploadBatchRequest.builder("website", items)
                .visibility(Visibility.PUBLIC)
                .build())
        .data();
```

Explorer and recycle-bin batch results expose `failedItems`; they do not convert the backend's
per-item result into an all-or-nothing SDK result.

## Signed and public-site downloads

Signing returns a relative URI. Passing it to `downloadSigned` deliberately suppresses Bearer
credentials:

```java
var signed = client.signing()
        .signDownload(SigningClient.SignDownloadRequest.of("documents", "reports/2026.pdf"))
        .data();

try (var download = client.objects().downloadSigned(signed.path())) {
    download.body().transferTo(outputStream);
}
```

Public site routes by ID and absolute custom-domain routes also never carry a Bearer token:

```java
try (var page = client.sites().downloadPublished(42, "assets/app.js")) {
    page.body().transferTo(outputStream);
}

try (var page = client.sites().downloadDomain(URI.create("https://docs.example.com/guide/"))) {
    page.body().transferTo(outputStream);
}
```

## Errors, pagination, and concurrency

All SDK exceptions are unchecked. `LightOssApiException` provides the HTTP status, backend code,
service message, and request ID. Transport, timeout, protocol, configuration, and validation
failures have dedicated exception types. A request ID generated before network I/O is preserved
on transport failures.

```java
try {
    client.buckets().create("example");
} catch (LightOssApiException error) {
    System.err.printf("HTTP %d, code=%s, request=%s%n",
            error.statusCode(), error.code(), error.requestId().orElse("unknown"));
}
```

Paginated calls return immutable `Page<T>` values with an explicit `nextCursor`; fetching another
page always requires another method call. A shared client can be used from virtual threads:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var first = executor.submit(() -> client.objects().list(
            ObjectClient.ListObjectsRequest.builder("photos").prefix("2026/").build()));
    var second = executor.submit(() -> client.buckets().list());
    first.get();
    second.get();
}
```

## Domains

`LightOssClient` exposes `health()`, `system()`, `buckets()`, `explorer()`, `objects()`,
`recycleBin()`, `sites()`, and `signing()`. Public response models do not expose Jackson,
`HttpRequest`, or asynchronous types. Response enums that may grow are represented as open value
types so unknown server values remain readable.

## Build and contract verification

```powershell
mvn.cmd -B clean test
mvn.cmd -B test "-Dlightoss.openapi.file=../onlikee-light-oss/backend/docs/openapi.apifox.json"
mvn.cmd -B clean package
./scripts/check-openapi-contract.ps1
```

The default test uses the checked-in contract snapshot. The property-based form validates the
current local backend file without fetching, pulling, or starting the service. Maven Central
packaging is documented in [docs/maven-central-publishing.zh-CN.md](docs/maven-central-publishing.zh-CN.md).

## Compatibility

The 0.1.x line treats published public signatures as stable. Public APIs are not removed or
changed incompatibly except when required by a security issue or a breaking backend contract.

## License

Apache License 2.0.
