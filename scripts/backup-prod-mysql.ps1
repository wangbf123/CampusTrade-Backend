param(
    [string]$ComposeFile = "docker-compose.prod.yml",
    [string]$EnvFile = ".env.prod",
    [string]$OutputDir = "backups"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ComposeFile)) {
    throw "Compose file does not exist: $ComposeFile"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputPath = Join-Path $OutputDir "campustrade-mysql-$timestamp.sql"

Write-Host "Creating MySQL backup: $outputPath" -ForegroundColor Cyan

if (-not (Test-Path $EnvFile)) {
    throw ".env.prod does not exist. Copy .env.prod.example to .env.prod and fill production credentials first."
}

$envVars = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') {
        return
    }
    $pair = $_ -split '=', 2
    if ($pair.Length -eq 2) {
        $envVars[$pair[0]] = $pair[1]
    }
}

$database = $envVars["MYSQL_DATABASE"]
$username = $envVars["MYSQL_USERNAME"]
$password = $envVars["MYSQL_PASSWORD"]
if (-not $database -or -not $username -or -not $password) {
    throw ".env.prod must define MYSQL_DATABASE / MYSQL_USERNAME / MYSQL_PASSWORD"
}

$dumpCommand = "exec mysqldump -u$username -p$password --single-transaction --quick --routines --events $database"
$result = & docker compose --env-file $EnvFile -f $ComposeFile exec -T mysql sh -lc $dumpCommand
if ($LASTEXITCODE -ne 0) {
    throw "mysqldump failed."
}

Set-Content -LiteralPath $outputPath -Value $result -Encoding UTF8
Write-Host "Backup completed: $outputPath" -ForegroundColor Green
