param(
    [string]$Profile = "redis,rabbitmq",
    [int]$Port = 8080,
    [int]$StartupTimeoutSeconds = 90,
    [int]$ScenarioRequests = 300,
    [int]$Concurrency = 30,
    [int]$AppointmentRequests = 12,
    [int]$AsyncDrainSeconds = 8
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$jar = Join-Path $root "target\campus-trade-backend-0.1.0-SNAPSHOT.jar"
$log = Join-Path $root "target\app-pressure.combined.log"
$baseUrl = "http://localhost:$Port"

if (-not (Test-Path $jar)) {
    throw "Jar not found: $jar. Run mvn -DskipTests package first."
}

if (Test-Path $log) {
    Remove-Item -LiteralPath $log -Force
}

$existingPort = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
if ($existingPort) {
    throw "Port $Port is already in use. Stop the existing process or pass another -Port."
}

$job = Start-Job -Name "campustrade-pressure-app" -ArgumentList $root, $jar, $Profile, $Port, $log -ScriptBlock {
    param($root, $jar, $profile, $port, $log)
    Set-Location $root
    & java -jar $jar "--spring.profiles.active=$profile" "--server.port=$port" *> $log
}

try {
    $ready = $false
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($job.State -ne "Running") {
            $tail = if (Test-Path $log) { Get-Content $log -Tail 120 | Out-String } else { "" }
            throw "Application job stopped before readiness. Log tail:`n$tail"
        }
        try {
            $response = Invoke-WebRequest -Uri "$baseUrl/api/items" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            # Keep waiting until timeout.
        }
    }

    if (-not $ready) {
        $tail = if (Test-Path $log) { Get-Content $log -Tail 120 | Out-String } else { "" }
        throw "Application did not become ready in $StartupTimeoutSeconds seconds. Log tail:`n$tail"
    }

    $env:BASE_URL = $baseUrl
    $env:SPRING_PROFILES_ACTIVE = $Profile
    $env:SCENARIO_REQUESTS = [string]$ScenarioRequests
    $env:CONCURRENCY = [string]$Concurrency
    $env:APPOINTMENT_REQUESTS = [string]$AppointmentRequests

    & node (Join-Path $root "scripts\pressure-test.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "Pressure test failed with exit code $LASTEXITCODE."
    }

    if ($AsyncDrainSeconds -gt 0) {
        Start-Sleep -Seconds $AsyncDrainSeconds
    }
} finally {
    if ($job.State -eq "Running") {
        Stop-Job $job | Out-Null
    }
    Receive-Job $job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}
