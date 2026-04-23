param(
    [string]$BaseUrl = "http://127.0.0.1:8080/api/v1",
    [string]$AdminCredential = "admin@medstore.vn",
    [string]$AdminPassword = "Admin@123",
    [string]$EmployeeCredential = "employee@medstore.vn",
    [string]$EmployeePassword = "Employee@123"
)

$ErrorActionPreference = "Stop"

function Get-WebErrorBody {
    param([object]$ErrorRecord)

    $exception = if ($ErrorRecord -is [System.Management.Automation.ErrorRecord]) {
        $ErrorRecord.Exception
    } elseif ($ErrorRecord -is [System.Exception]) {
        $ErrorRecord
    } else {
        return "$ErrorRecord"
    }

    $response = $exception.Response
    if ($null -eq $response) { return $exception.Message }

    try {
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        return $reader.ReadToEnd()
    } catch {
        return $exception.Message
    }
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 30
            return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -ContentType "application/json" -Body $json
        }
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers
    } catch {
        $errorBody = Get-WebErrorBody -ErrorRecord $_
        $statusCode = $null
        if ($_.Exception -and $_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if ([string]::IsNullOrWhiteSpace($errorBody)) {
            $errorBody = $_.Exception.Message
        }
        throw "[$Method] $Url failed (status=$statusCode): $errorBody"
    }
}

function Login {
    param(
        [string]$Credential,
        [string]$Password
    )

    $resp = Invoke-JsonApi -Method Post -Url "$BaseUrl/auth/login" -Body @{
        credential = $Credential
        password = $Password
    }

    if ([string]::IsNullOrWhiteSpace($resp.accessToken)) {
        throw "Login succeeded but accessToken is empty for $Credential"
    }

    return $resp
}

Write-Host "=== E2E Delete-Request + Personnel API ==="

Write-Host "[1/10] Login employee/admin"
$employee = Login -Credential $EmployeeCredential -Password $EmployeePassword
$admin = Login -Credential $AdminCredential -Password $AdminPassword

$employeeHeaders = @{ Authorization = "Bearer $($employee.accessToken)" }
$adminHeaders = @{ Authorization = "Bearer $($admin.accessToken)" }

Write-Host "[2/10] Admin creates target product for delete-request flow"
$productStamp = Get-Date -Format "yyMMddHHmmss"
$targetProductName = "E2E DeleteReq Product $productStamp"
$createdProduct = Invoke-JsonApi -Method Post -Url "$BaseUrl/products" -Headers $adminHeaders -Body @{
    categoryId = "cat-supplies"
    name = $targetProductName
    brand = "E2E"
    origin = "Viet Nam"
    unit = "Hop"
    price = 99000
    originalPrice = 99000
    discountPct = 0
    rewardPoints = 0
    productType = "MEDICAL_SUPPLY"
    riskClassification = "A"
    requiresCertification = $false
    requiresConsultation = $false
    isActive = $true
    attributes = @{}
}
$productId = $createdProduct.data.id
$productName = if ([string]::IsNullOrWhiteSpace($createdProduct.data.name)) { $targetProductName } else { $createdProduct.data.name }
if ([string]::IsNullOrWhiteSpace($productId)) {
    throw "Create target product failed: missing product id"
}
Write-Host "Target product: $productName ($productId)"

Write-Host "[3/10] Employee submits delete request #1 (for reject path)"
$req1 = Invoke-JsonApi -Method Post -Url "$BaseUrl/internal/products/$productId/delete-request" -Headers $employeeHeaders -Body @{
    reason = "E2E reject path - $(Get-Date -Format s)"
}
$requestId1 = $req1.data.id
if ([string]::IsNullOrWhiteSpace($requestId1)) {
    throw "Delete request #1 did not return request id"
}

Write-Host "[4/10] Admin checks pending delete requests"
$pendingAfterReq1 = Invoke-JsonApi -Method Get -Url "$BaseUrl/admin/product-delete-requests?status=PENDING" -Headers $adminHeaders
$containsReq1 = @($pendingAfterReq1.data | Where-Object { $_.id -eq $requestId1 }).Count -gt 0
if (-not $containsReq1) {
    throw "Pending list does not contain request #1: $requestId1"
}

Write-Host "[5/10] Admin rejects delete request #1"
$null = Invoke-JsonApi -Method Post -Url "$BaseUrl/admin/product-delete-requests/$requestId1/review" -Headers $adminHeaders -Body @{ approve = $false }

Write-Host "[6/10] Employee submits delete request #2 (for approve path)"
$req2 = Invoke-JsonApi -Method Post -Url "$BaseUrl/internal/products/$productId/delete-request" -Headers $employeeHeaders -Body @{
    reason = "E2E approve path - $(Get-Date -Format s)"
}
$requestId2 = $req2.data.id
if ([string]::IsNullOrWhiteSpace($requestId2)) {
    throw "Delete request #2 did not return request id"
}

Write-Host "[7/10] Admin approves delete request #2"
$null = Invoke-JsonApi -Method Post -Url "$BaseUrl/admin/product-delete-requests/$requestId2/review" -Headers $adminHeaders -Body @{ approve = $true }

Write-Host "[8/10] Admin creates personnel account"
$stamp = Get-Date -Format "yyMMddHHmmss"
$newPhone = "09$stamp"
$newEmail = "e2e+$stamp@medstore.vn"
$newPassword = "Temp@123"
$newRole = "EMPLOYEE"

$createResp = Invoke-JsonApi -Method Post -Url "$BaseUrl/admin/users" -Headers $adminHeaders -Body @{
    fullName = "E2E Staff $stamp"
    phone = $newPhone
    email = $newEmail
    password = $newPassword
    role = $newRole
}
$newUserId = $createResp.data.id
if ([string]::IsNullOrWhiteSpace($newUserId)) {
    throw "Create user did not return id"
}

Write-Host "[9/10] Admin updates + lock/unlock + reset password"
$null = Invoke-JsonApi -Method Put -Url "$BaseUrl/admin/users/$newUserId" -Headers $adminHeaders -Body @{
    fullName = "E2E Updated $stamp"
    role = "EMPLOYEE"
}

# Toggle lock (lock)
$null = Invoke-JsonApi -Method Put -Url "$BaseUrl/admin/users/$newUserId/ban" -Headers $adminHeaders
# Toggle lock again (unlock)
$null = Invoke-JsonApi -Method Put -Url "$BaseUrl/admin/users/$newUserId/ban" -Headers $adminHeaders

$null = Invoke-JsonApi -Method Post -Url "$BaseUrl/admin/users/$newUserId/reset-password" -Headers $adminHeaders -Body @{
    newPassword = "Reset@123"
}

Write-Host "[10/10] Verify user is in staff list"
$usersResp = Invoke-JsonApi -Method Get -Url "$BaseUrl/admin/users" -Headers $adminHeaders
$createdUser = @($usersResp.data | Where-Object { $_.id -eq $newUserId })[0]
if ($null -eq $createdUser) {
    throw "Created user not found in admin user list"
}

$result = [ordered]@{
    product = [ordered]@{
        id = $productId
        name = $productName
        rejectRequestId = $requestId1
        approveRequestId = $requestId2
    }
    personnel = [ordered]@{
        userId = $newUserId
        phone = $newPhone
        email = $newEmail
        finalRole = $createdUser.role
        isActive = $createdUser.isActive
    }
    status = "PASS"
    finishedAt = (Get-Date).ToString("s")
}

Write-Host "E2E passed." -ForegroundColor Green
$result | ConvertTo-Json -Depth 10




