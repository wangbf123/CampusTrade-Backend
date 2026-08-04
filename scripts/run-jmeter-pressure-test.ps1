param(
    [string]$JMeterHome = "D:\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
    [string]$Profile = "",
    [int]$Port = 18080,
    [int]$StartupTimeoutSeconds = 90,
    [int]$Concurrency = 30,
    [int]$ScenarioLoops = 10,
    [int]$WarmupThreads = 10,
    [int]$WarmupLoops = 5,
    [int]$AppointmentThreads = 6,
    [int]$AppointmentLoops = 2,
    [int]$RateProbeThreads = 5,
    [int]$RateProbeLoops = 3
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$jar = Join-Path $root "target\campus-trade-backend-0.1.0-SNAPSHOT.jar"
$jmeter = Join-Path $JMeterHome "bin\jmeter.bat"
$jmx = Join-Path $root "scripts\jmeter-campus-trade.jmx"
$outputDir = Join-Path $root "docs\pressure-results"
$tag = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH-mm-ss-fffZ")
$jtl = Join-Path $outputDir "jmeter-$tag.jtl"
$jmeterLog = Join-Path $outputDir "jmeter-$tag.log"
$appLog = Join-Path $root "target\app-jmeter-pressure.log"
$baseUrl = "http://localhost:$Port"

if (-not (Test-Path $jmeter)) {
    throw "JMeter not found: $jmeter"
}
if (-not (Test-Path $jar)) {
    throw "Jar not found: $jar. Run mvn -DskipTests package first."
}
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}
if (Test-Path $appLog) {
    Remove-Item -LiteralPath $appLog -Force
}

$existingPort = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
if ($existingPort) {
    throw "Port $Port is already in use. Stop the existing process or pass another -Port."
}

$arguments = @("-jar", $jar, "--server.port=$Port")
if ($Profile -ne "") {
    $arguments += "--spring.profiles.active=$Profile"
}

$job = Start-Job -Name "campustrade-jmeter-app" -ArgumentList $root, $arguments, $appLog -ScriptBlock {
    param($root, $arguments, $appLog)
    Set-Location $root
    & java @arguments *> $appLog
}

try {
    $ready = $false
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($job.State -ne "Running") {
            $tail = if (Test-Path $appLog) { Get-Content $appLog -Tail 120 | Out-String } else { "" }
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
        $tail = if (Test-Path $appLog) { Get-Content $appLog -Tail 120 | Out-String } else { "" }
        throw "Application did not become ready in $StartupTimeoutSeconds seconds. Log tail:`n$tail"
    }

    $env:BASE_URL = $baseUrl
    $env:SPRING_PROFILES_ACTIVE = if ($Profile -eq "") { "default-inmemory" } else { $Profile }

    & $jmeter `
        -n `
        -t $jmx `
        -l $jtl `
        -j $jmeterLog `
        -Jhost "localhost" `
        -Jport "$Port" `
        -Jprotocol "http" `
        -Jconcurrency "$Concurrency" `
        -JscenarioLoops "$ScenarioLoops" `
        -JwarmupThreads "$WarmupThreads" `
        -JwarmupLoops "$WarmupLoops" `
        -JappointmentThreads "$AppointmentThreads" `
        -JappointmentLoops "$AppointmentLoops" `
        -JrateProbeThreads "$RateProbeThreads" `
        -JrateProbeLoops "$RateProbeLoops"
    if ($LASTEXITCODE -ne 0) {
        throw "JMeter failed with exit code $LASTEXITCODE. See $jmeterLog"
    }

    & node (Join-Path $root "scripts\summarize-jmeter.mjs") $jtl $outputDir
    if ($LASTEXITCODE -ne 0) {
        throw "JMeter summary failed with exit code $LASTEXITCODE."
    }
} finally {
    if ($job.State -eq "Running") {
        Stop-Job $job | Out-Null
    }
    Receive-Job $job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}
