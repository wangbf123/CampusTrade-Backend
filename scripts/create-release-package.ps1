param(
    [string]$Version = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [string]$OutputDir = "dist",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$artifactName = "campustrade-release-$Version"
$distRoot = Join-Path $repoRoot $OutputDir
$stagingRoot = Join-Path $distRoot $artifactName
$zipPath = Join-Path $distRoot "$artifactName.zip"

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & mvn -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed."
        }
    }
    finally {
        Pop-Location
    }
}

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
if (Test-Path $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

$includePaths = @(
    "pom.xml",
    "src",
    "Dockerfile",
    "docker-compose.prod.yml",
    ".dockerignore",
    ".env.prod.example",
    "README.md",
    "deploy",
    "docs\schema.sql",
    "docs\go-live-checklist.md",
    "docs\server-deployment-guide.md",
    "scripts\start-prod-local.ps1",
    "scripts\smoke-prod.ps1",
    "scripts\backup-prod-mysql.ps1"
)

foreach ($relativePath in $includePaths) {
    $source = Join-Path $repoRoot $relativePath
    if (-not (Test-Path $source)) {
        Write-Warning "Skipped missing path: $relativePath"
        continue
    }

    $target = Join-Path $stagingRoot $relativePath
    $targetParent = Split-Path $target -Parent
    New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
}

$jar = Join-Path $repoRoot "target\campus-trade-backend-0.1.0-SNAPSHOT.jar"
if (Test-Path $jar) {
    $targetJarDir = Join-Path $stagingRoot "target"
    New-Item -ItemType Directory -Force -Path $targetJarDir | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $targetJarDir "campus-trade-backend-0.1.0-SNAPSHOT.jar") -Force
}

Compress-Archive -Path (Join-Path $stagingRoot "*") -DestinationPath $zipPath -Force

Write-Host "Release package created: $zipPath" -ForegroundColor Green
