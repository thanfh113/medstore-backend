param(
    [string]$BaseUrl = "http://127.0.0.1:8080/api/v1",
    [string]$Credential = "employee@medstore.vn",
    [string]$Password = "Employee@123",
    [int]$PushCount = 50
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    if ($Body -ne $null) {
        $json = $Body | ConvertTo-Json -Depth 20
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers
}

Write-Host "[1/5] Login..."
$login = Invoke-Json -Method Post -Url "$BaseUrl/auth/login" -Body @{ credential = $Credential; password = $Password }
$token = $login.accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "Cannot get access token" }
$headers = @{ Authorization = "Bearer $token" }

$deviceId = "stress-device-$([guid]::NewGuid().ToString())"
$clientMutations = @()

Write-Host "[2/5] Push stress ($PushCount items)..."
$changes = @()
for ($i = 1; $i -le $PushCount; $i++) {
    $mutationId = [guid]::NewGuid().ToString()
    $clientMutations += $mutationId
    $changes += @{
        entityType = "ORDER_STATUS"
        entityId = "order-stress-$i"
        operation = "UPDATE"
        clientMutationId = $mutationId
        payload = @{
            orderId = "order-stress-$i"
            status = "PROCESSING"
            source = "stress-replay-script"
        }
    }
}

$pushBody = @{ deviceId = $deviceId; changes = $changes }
$push = Invoke-Json -Method Post -Url "$BaseUrl/internal/sync/push" -Headers $headers -Body $pushBody

Write-Host "[3/5] Replay same push to validate idempotency..."
$replay = Invoke-Json -Method Post -Url "$BaseUrl/internal/sync/push" -Headers $headers -Body $pushBody

Write-Host "[4/5] Pull incremental..."
$pull = Invoke-Json -Method Get -Url "$BaseUrl/internal/sync/pull?deviceId=$deviceId&sinceVersion=0&limit=1000" -Headers $headers

Write-Host "[5/5] Summary"
$applied = @($push.accepted | Where-Object { $_.status -eq "APPLIED" }).Count
$duplicate = @($replay.accepted | Where-Object { $_.status -eq "DUPLICATE" }).Count
$pulledCount = @($pull.data).Count
$latestVersion = $pull.latestServerVersion

Write-Host "Applied in first push : $applied"
Write-Host "Duplicate in replay   : $duplicate"
Write-Host "Pulled changes        : $pulledCount"
Write-Host "Latest server version : $latestVersion"

if ($applied -lt $PushCount) {
    throw "Expected at least $PushCount APPLIED changes in first push"
}
if ($duplicate -lt $PushCount) {
    throw "Expected at least $PushCount DUPLICATE changes in replay push"
}

Write-Host "Stress + replay sync test passed." -ForegroundColor Green

