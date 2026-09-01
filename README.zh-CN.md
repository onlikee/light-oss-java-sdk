# Light OSS Java SDK

[English documentation](README.md)

面向 Light OSS HTTP API 的 Java 21 同步 SDK。本库对应 Light OSS 的 Go/Gin API，不是 Amazon
S3 客户端。0.1.0 覆盖 38 个 OpenAPI 操作，以及公开站点自定义域名的 GET/HEAD 路由。

## 环境与依赖

- Java 21 或更高版本
- Light OSS 服务源站地址，例如 `https://oss.example.com`

```xml
<dependency>
  <groupId>com.onlikee</groupId>
  <artifactId>light-oss-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

客户端不可变且线程安全。底层使用 JDK HTTP Client，默认不重试、不跟随重定向，也不会自动翻页。

## 创建客户端

```java
import com.onlikee.lightoss.LightOssClient;
import java.net.URI;

try (LightOssClient client = LightOssClient.builder(URI.create("https://oss.example.com"))
        .bearerToken(System.getenv("LIGHT_OSS_TOKEN"))
        .build()) {
    var buckets = client.buckets().list().data();
}
```

轮换凭据可使用线程安全的 token provider；每个受保护请求都会重新读取：

```java
LightOssClient client = LightOssClient.builder(URI.create("https://oss.example.com"))
        .tokenProvider(tokenStore::currentToken)
        .build();
```

连接超时默认 10 秒，请求总超时默认不设置。Builder 还可以配置请求总超时、request ID provider，
或注入由调用方管理的 `HttpClient`。

## 上传与下载

`UploadSource` 支持 `Path`、防御性复制的 `byte[]`，以及可重复打开的输入流 supplier。Path 和
输入流都直接流式发送。

```java
var upload = ObjectClient.UploadObjectRequest.builder(
        "documents",
        "reports/2026.pdf",
        UploadSource.fromPath(Path.of("report.pdf"), "application/pdf"))
    .visibility(Visibility.PRIVATE)
    .allowOverwrite(false)
    .build();

var object = client.objects().upload(upload).data();
```

下载与文件夹 ZIP 都是流式响应，必须关闭：

```java
var request = ObjectClient.DownloadObjectRequest.builder("documents", "reports/2026.pdf")
        .forceDownload(true)
        .build();

try (var download = client.objects().download(request)) {
    download.body().transferTo(outputStream);
    download.etag().ifPresent(System.out::println);
}
```

## 批量操作

对象批量上传和站点批量发布保持后端整批事务语义。SDK 会生成 multipart 边界、文件字段名和
manifest：

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

Explorer 与回收站批量接口会保留后端的 `failedItems` 逐项结果，不会擅自改成 SDK 侧整批失败。

## 签名下载与公开站点

签名接口返回相对 URI。把它交给 `downloadSigned` 时，SDK 明确不会附加 Bearer：

```java
var signed = client.signing()
        .signDownload(SigningClient.SignDownloadRequest.of("documents", "reports/2026.pdf"))
        .data();

try (var download = client.objects().downloadSigned(signed.path())) {
    download.body().transferTo(outputStream);
}
```

按站点 ID 访问公开路由、访问绝对自定义域名 URI 时同样不会携带 Bearer：

```java
try (var page = client.sites().downloadPublished(42, "guide/")) {
    page.body().transferTo(outputStream);
}

try (var page = client.sites().downloadDomain(URI.create("https://docs.example.com/guide/"))) {
    page.body().transferTo(outputStream);
}
```

## 异常、分页与并发

所有 SDK 异常都是非受检异常。`LightOssApiException` 提供 HTTP 状态、后端 code、服务消息和
request ID；传输、超时、协议、配置和参数校验都有独立异常类型。发生网络故障时，异常仍会携带
发起请求前生成的 request ID。仅含状态码或非 JSON 的站点错误使用明确的 SDK code
`sdk_http_error`，同时保留 HTTP 状态。

```java
try {
    client.buckets().create("example");
} catch (LightOssApiException error) {
    System.err.printf("HTTP %d, code=%s, request=%s%n",
            error.statusCode(), error.code(), error.requestId().orElse("unknown"));
}
```

分页接口返回不可变 `Page<T>` 和显式 `nextCursor`，获取下一页必须再次调用方法。共享客户端可由
虚拟线程并发使用：

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var first = executor.submit(() -> client.objects().list(
            ObjectClient.ListObjectsRequest.builder("photos").prefix("2026/").build()));
    var second = executor.submit(() -> client.buckets().list());
    first.get();
    second.get();
}
```

## 领域与扩展边界

`LightOssClient` 提供 `health()`、`system()`、`buckets()`、`explorer()`、`objects()`、
`recycleBin()`、`sites()`、`signing()` 八个领域入口。公共模型不暴露 Jackson、`HttpRequest`
或异步类型。可能由后端扩展的响应枚举使用开放值类型，因此读取未来新增值不会失败。

## 构建与合同校验

```powershell
mvn.cmd -B clean test
mvn.cmd -B test "-Dlightoss.openapi.file=../onlikee-light-oss/backend/docs/openapi.apifox.json"
mvn.cmd -B clean package
./scripts/check-openapi-contract.ps1
```

默认测试读取仓内合同快照；通过属性可直接校验本地后端 OpenAPI 文件，不会 fetch、pull 或启动
后端。Maven Central 打包与人工发布流程见
[docs/maven-central-publishing.zh-CN.md](docs/maven-central-publishing.zh-CN.md)。

## 兼容性

0.1.x 将已经发布的公共签名视为稳定合同。除安全问题或后端破坏性合同变化外，不删除或不兼容地
修改公共 API。

## 许可证

Apache License 2.0。
