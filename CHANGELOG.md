# Changelog

All notable changes to this project are documented in this file.

## 0.1.0

- Added the immutable, thread-safe `LightOssClient` and eight domain clients.
- Covered all 38 backend OpenAPI operations and public custom-domain site routing.
- Added streamed object, ZIP, multipart batch, and site publication transfers.
- Added explicit response, pagination, open-value, sealed explorer-entry, and exception models.
- Added credential-scope controls, configurable token/request-ID providers, and optional request timeouts.
- Added JDK HTTP wire, streaming, error, concurrency, and local OpenAPI contract tests.
- Added English and Simplified Chinese usage documentation.
- Aligned object, recycle-bin, and explorer defaults with the Go backend contract.
- Preserved backend-significant request text, supported directory-style site paths and empty index-document defaults, and retained HTTP status for bodyless or non-JSON site errors.
- Locked direct-upload media types, ZIP headers, site error representations, and HEAD response bodies into the OpenAPI contract tests.
