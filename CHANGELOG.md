# Changelog

All notable changes to this project are documented in this file.

## 0.3.0

- Aligned the Maven artifact version, documentation, and HTTP User-Agent with version `0.3.0`.
- Added `SigningClient.signUpload`, the `SignedUpload` model, and `ObjectClient.uploadSigned`, extending coverage to 39 OpenAPI operations.
- Reused streamed upload sources for signed uploads without attaching configured Bearer credentials, including support for unknown-length sources.
- Preserved raw filenames and server-provided signed headers while rejecting credential and HTTP transport-framing headers.
- Unified signed-path validation and expanded HTTP, streaming, model-validation, and OpenAPI contract coverage.

## 0.2.0

- Added opt-in real-backend integration coverage (`d386bbf`).
- Aligned object, recycle-bin, and explorer defaults with the Go backend contract.
- Preserved backend-significant request text, supported directory-style site paths and empty index-document defaults, and retained HTTP status for bodyless or non-JSON site errors.
- Locked direct-upload media types, ZIP headers, site error representations, and HEAD response bodies into the OpenAPI contract tests.
- Updated the Maven version and English usage documentation for version `0.2.0` (`5546fea`, `d107542`).

## 0.1.0

- Added the immutable, thread-safe `LightOssClient` and eight domain clients.
- Covered all 38 backend OpenAPI operations and public custom-domain site routing.
- Added streamed object, ZIP, multipart batch, and site publication transfers.
- Added explicit response, pagination, open-value, sealed explorer-entry, and exception models.
- Added credential-scope controls, configurable token/request-ID providers, and optional request timeouts.
- Added JDK HTTP wire, streaming, error, concurrency, and local OpenAPI contract tests.
- Added English and Simplified Chinese usage documentation.
