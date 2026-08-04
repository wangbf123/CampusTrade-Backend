param(
    [string]$BaseUrl = "http://localhost",
    [int]$HealthTimeoutSeconds = 240,
    [int]$PollIntervalSeconds = 5,
    [int]$AsyncDrainSeconds = 8,
    [string]$InviteCode = $null
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Wait-Health {
    param(
        [string]$Url,
        [int]$TimeoutSeconds,
        [int]$IntervalSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Method Get -Uri "$Url/actuator/health" -TimeoutSec 10
            if ($response.status -eq "UP") {
                Write-Host "Health check is ready." -ForegroundColor Green
                return
            }
        } catch {
            Start-Sleep -Seconds $IntervalSeconds
            continue
        }
        Start-Sleep -Seconds $IntervalSeconds
    }

    throw "Health check did not become UP within $TimeoutSeconds seconds."
}

function Invoke-ApiJson {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [hashtable]$ExtraHeaders = @{}
    )

    $headers = @{}
    foreach ($key in $ExtraHeaders.Keys) {
        $headers[$key] = $ExtraHeaders[$key]
    }
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $params = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ContentType = "application/json"
        TimeoutSec  = 20
    }
    if ($null -ne $Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 10)
    }

    $response = Invoke-RestMethod @params
    if ($response.code -ne 0) {
        throw "API $Method $Path failed: $($response.message)"
    }
    return $response.data
}

function Invoke-UploadImage {
    param(
        [string]$Path,
        [string]$Token
    )

    $raw = & curl.exe -s `
        -X POST `
        -H "Authorization: Bearer $Token" `
        -F "file=@$Path;type=image/png" `
        "$BaseUrl/api/files/images"

    if ($LASTEXITCODE -ne 0) {
        throw "curl upload command failed."
    }

    $response = $raw | ConvertFrom-Json
    if ($response.code -ne 0) {
        throw "Image upload failed: $($response.message)"
    }
    return $response.data
}

function New-FakeImageFile {
    $tempFile = Join-Path $env:TEMP ("campustrade-smoke-" + [guid]::NewGuid().ToString("N") + ".png")
    [System.IO.File]::WriteAllBytes($tempFile, [byte[]](137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,2,0,0,0,144,119,83,222,0,0,0,12,73,68,65,84,8,153,99,248,15,4,0,9,251,3,253,160,104,47,120,0,0,0,0,73,69,78,68,174,66,96,130))
    return $tempFile
}

Write-Step "Waiting for production health endpoint"
Wait-Health -Url $BaseUrl -TimeoutSeconds $HealthTimeoutSeconds -IntervalSeconds $PollIntervalSeconds

$suffix = Get-Date -Format "yyyyMMddHHmmss"
$sellerUsername = "seller$suffix"
$buyerUsername = "buyer$suffix"
$password = "Pass123456"

Write-Step "Registering seller and buyer users"
$sellerRegisterBody = @{
    username = $sellerUsername
    password = $password
    nickname = "Smoke Seller"
    phone    = "18800000001"
    campus   = "East Campus"
}
$buyerRegisterBody = @{
    username = $buyerUsername
    password = $password
    nickname = "Smoke Buyer"
    phone    = "18800000002"
    campus   = "East Campus"
}
if ($InviteCode) {
    $sellerRegisterBody["inviteCode"] = $InviteCode
    $buyerRegisterBody["inviteCode"] = $InviteCode
}
$sellerAuth = Invoke-ApiJson -Method Post -Path "/api/auth/register" -Body $sellerRegisterBody
$buyerAuth = Invoke-ApiJson -Method Post -Path "/api/auth/register" -Body $buyerRegisterBody

$sellerToken = $sellerAuth.token
$buyerToken = $buyerAuth.token

$imagePath = New-FakeImageFile
try {
    Write-Step "Uploading item image"
    $upload = Invoke-UploadImage -Path $imagePath -Token $sellerToken

    Write-Step "Creating item"
    $item = Invoke-ApiJson -Method Post -Path "/api/items" -Token $sellerToken -Body @{
        title          = "Smoke Test iPad"
        description    = "Production smoke test item"
        category       = "Electronics"
        price          = 1999.00
        conditionLevel = "LIKE_NEW"
        campus         = "East Campus"
        tradePlace     = "Library Gate"
        imageUrls      = @($upload.url)
    }

    Write-Step "Reading item detail and hot list"
    $null = Invoke-ApiJson -Method Get -Path "/api/items/$($item.id)" -Token $buyerToken
    $hotItems = Invoke-ApiJson -Method Get -Path "/api/items/hot?limit=5" -Token $buyerToken
    if (-not ($hotItems | Where-Object { $_.id -eq $item.id })) {
        throw "Hot item list does not contain the created item."
    }

    Write-Step "Creating appointment with idempotency key"
    $appointment = Invoke-ApiJson -Method Post -Path "/api/items/$($item.id)/appointments" -Token $buyerToken -ExtraHeaders @{
        "X-Idempotency-Key" = [guid]::NewGuid().ToString()
    } -Body @{
        expectedTime = (Get-Date).AddHours(2).ToString("yyyy-MM-ddTHH:mm:ss")
        note         = "Production smoke appointment"
    }
    if ($appointment.status -ne "PENDING") {
        throw "Appointment status should be PENDING, actual: $($appointment.status)"
    }

    Write-Step "Seller confirming appointment"
    $confirmed = Invoke-ApiJson -Method Post -Path "/api/orders/$($appointment.id)/confirm" -Token $sellerToken
    if ($confirmed.status -ne "CONFIRMED") {
        throw "Order status should be CONFIRMED, actual: $($confirmed.status)"
    }

    Write-Step "Waiting for async notification delivery"
    Start-Sleep -Seconds $AsyncDrainSeconds
    $buyerMessages = Invoke-ApiJson -Method Get -Path "/api/messages" -Token $buyerToken
    if (($buyerMessages | Measure-Object).Count -lt 1) {
        throw "Buyer should receive at least one async message."
    }

    Write-Step "Buyer completing trade"
    $completed = Invoke-ApiJson -Method Post -Path "/api/orders/$($appointment.id)/complete" -Token $buyerToken
    if ($completed.status -ne "COMPLETED") {
        throw "Order status should be COMPLETED, actual: $($completed.status)"
    }

    Start-Sleep -Seconds $AsyncDrainSeconds
    $sellerMessages = Invoke-ApiJson -Method Get -Path "/api/messages" -Token $sellerToken
    if (($sellerMessages | Measure-Object).Count -lt 1) {
        throw "Seller should receive at least one async message."
    }

    Write-Step "Production smoke test passed"
    [pscustomobject]@{
        baseUrl        = $BaseUrl
        sellerUsername = $sellerUsername
        buyerUsername  = $buyerUsername
        itemId         = $item.id
        orderId        = $appointment.id
        buyerMessages  = ($buyerMessages | Measure-Object).Count
        sellerMessages = ($sellerMessages | Measure-Object).Count
    } | Format-List
}
finally {
    if (Test-Path $imagePath) {
        Remove-Item -LiteralPath $imagePath -Force
    }
}
