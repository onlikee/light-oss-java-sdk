param(
    [string]$OpenApiFile = "../onlikee-light-oss/backend/docs/openapi.apifox.json"
)

$ErrorActionPreference = "Stop"
$sdkRoot = Split-Path -Parent $PSScriptRoot
$resolvedOpenApi = (Resolve-Path -LiteralPath (Join-Path $sdkRoot $OpenApiFile)).Path

Push-Location $sdkRoot
try {
    & mvn.cmd -B test "-Dlightoss.openapi.file=$resolvedOpenApi"
    if ($LASTEXITCODE -ne 0) {
        throw "OpenAPI contract verification failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
