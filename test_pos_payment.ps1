#!/usr/bin/env pwsh
# Test POS Payment Flow
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$baseUrl = "http://localhost:8080/api/v1"

# 1. Login
Write-Host "1. Logging in..." -ForegroundColor Cyan
$loginHeaders = @{"Content-Type" = "application/json"}
$loginBody = @{
    credential = "admin@medstore.vn"
    password = "Admin@123"
} | ConvertTo-Json

try {
    $loginResponse = Invoke-RestMethod -Uri "$baseUrl/auth/login" -Method Post -Headers $loginHeaders -Body $loginBody
    
    # Login response is flat (no 'data' wrapper)
    $token = $loginResponse.accessToken
    if ($null -eq $token) {
        Write-Host "Login failed: 'accessToken' is null in response." -ForegroundColor Red
        Write-Host "Response: $($loginResponse | ConvertTo-Json)" -ForegroundColor Yellow
        exit 1
    }
    
    $displayToken = if ($token.Length -gt 20) { $token.Substring(0,20) } else { $token }
    Write-Host "Login successful. Token: $($displayToken)..." -ForegroundColor Green
} catch {
    Write-Host "Login failed with exception: $_" -ForegroundColor Red
    exit 1
}

# 2. Get products
Write-Host "`n2. Fetching products..." -ForegroundColor Cyan
$authHeaders = @{
    "Authorization" = "Bearer $token"
    "Content-Type"  = "application/json"
}

$amp = [char]38
$productsUri = "$baseUrl/internal/products?page=1$($amp)limit=10"

try {
    $productsResponse = Invoke-RestMethod -Uri $productsUri -Method Get -Headers $authHeaders
    if ($productsResponse.data -and $productsResponse.data.Count -gt 0) {
        $productId = $productsResponse.data[0].id
        $productPrice = $productsResponse.data[0].price
        Write-Host "Got product: $productId, Price: $productPrice" -ForegroundColor Green
    } else {
        Write-Host "No products found." -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "Failed to fetch products: $_" -ForegroundColor Red
    exit 1
}

# 3. Create POS Order with MOMO
Write-Host "`n3. Creating POS order with MOMO..." -ForegroundColor Cyan
$orderBody = @{
    items = @(
        @{
            productId = $productId
            quantity = 1
        }
    )
    paymentMethod = "MOMO"
} | ConvertTo-Json

try {
    $orderResponse = Invoke-RestMethod -Uri "$baseUrl/internal/pos/orders" -Method Post -Headers $authHeaders -Body $orderBody
    $orderId = $orderResponse.data.id
    $orderCode = $orderResponse.data.orderCode
    Write-Host "Order created: $orderCode (ID: $orderId)" -ForegroundColor Green
    Write-Host "Payment Status: $($orderResponse.data.paymentStatus)" -ForegroundColor Green
} catch {
    Write-Host "Failed to create order: $_" -ForegroundColor Red
    exit 1
}

# 4. Init Payment
Write-Host "`n4. Initializing MOMO payment..." -ForegroundColor Cyan
$paymentBody = @{
    paymentMethod = "MOMO"
} | ConvertTo-Json

try {
    $paymentResponse = Invoke-RestMethod -Uri "$baseUrl/internal/pos/orders/$orderId/init-payment" -Method Post -Headers $authHeaders -Body $paymentBody
    if ($paymentResponse.data) {
        Write-Host "Payment initialized successfully!" -ForegroundColor Green
        $qr = $paymentResponse.data.qrContent
        $qrDisp = if ($qr -and $qr.Length -gt 50) { $qr.Substring(0,50) + "..." } else { $qr }
        Write-Host "QR Content: $qrDisp" -ForegroundColor Green
        Write-Host "Payment Reference: $($paymentResponse.data.paymentReference)" -ForegroundColor Green
    } else {
        Write-Host "Payment init failed: $($paymentResponse | ConvertTo-Json)" -ForegroundColor Red
    }
} catch {
    Write-Host "Error initializing payment: $_" -ForegroundColor Red
}
