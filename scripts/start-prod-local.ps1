param(
    [string]$ComposeFile = "docker-compose.prod.yml",
    [string]$EnvFile = ".env.prod",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $EnvFile)) {
    if (-not (Test-Path ".env.prod.example")) {
        throw "Neither $EnvFile nor .env.prod.example exists."
    }
    Copy-Item .env.prod.example $EnvFile -Force
    Write-Host "Created $EnvFile from .env.prod.example. Please review credentials before real deployment." -ForegroundColor Yellow
}

$arguments = @("--env-file", $EnvFile, "-f", $ComposeFile, "up", "-d")
if (-not $NoBuild) {
    $arguments += "--build"
}

Write-Host ("Running: docker compose " + ($arguments -join " ")) -ForegroundColor Cyan
& docker compose @arguments
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up failed."
}

& docker compose --env-file $EnvFile -f $ComposeFile ps
