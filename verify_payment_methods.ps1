#!/usr/bin/env pwsh
# Quick Test Script for POS Payment Methods
# Usage: .\verify_payment_methods.ps1

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$base = "http://127.0.0.1:8080/api/v1"

Write-Host "`n╔════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║   POS Payment Method Verification Script  ║" -ForegroundColor Cyan
Write-Host "║   Generated: 2026-04-13                    ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════╝" -ForegroundColor Cyan

# Step 1: Authentication
Write-Host "`n[1/3] Authenticating..." -ForegroundColor Yellow
$loginBody = '{"credential":"admin@medstore.vn","password":"Admin@123"}'
try {
    $login = Invoke-RestMethod -Method POST -Uri "$base/auth/login" `
        -ContentType "application/json" -Body $loginBody -TimeoutSec 10
    $token = $login.accessToken
    Write-Host "✓ Authentication successful" -ForegroundColor Green
} catch {
    Write-Host "✗ Authentication failed: $_" -ForegroundColor Red
    exit 1
}

$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}

# Step 2: Fetch Products
Write-Host "`n[2/3] Fetching products..." -ForegroundColor Yellow
try {
    $products = Invoke-RestMethod -Method GET -Uri "$base/internal/products?page=1&limit=10" `
        -Headers $headers -TimeoutSec 10
    if ($products.data -and $products.data.Count -gt 0) {
        $productId = $products.data[0].id
        $productName = $products.data[0].name
        Write-Host "✓ Found product: $productName (ID: $productId)" -ForegroundColor Green
    } else {
        Write-Host "✗ No products found" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "✗ Failed to fetch products: $_" -ForegroundColor Red
    exit 1
}

# Step 3: Test Payment Methods
Write-Host "`n[3/3] Testing payment methods..." -ForegroundColor Yellow
$methods = @("VNPAY", "MOMO", "ZALOPAY")
$testResults = @()

foreach ($method in $methods) {
    $result = @{
        Method = $method
        Status = "UNKNOWN"
        Details = ""
    }

    try {
        # Create order
        $orderBody = @{
            items = @( @{ productId = $productId; quantity = 1 } )
            paymentMethod = $method
        } | ConvertTo-Json

        $order = Invoke-RestMethod -Method POST -Uri "$base/internal/pos/orders" `
            -Headers $headers -Body $orderBody -TimeoutSec 10

        $orderId = $order.data.id
        $orderCode = $order.data.orderCode

        # Initialize payment
        $paymentBody = @{ paymentMethod = $method } | ConvertTo-Json
        $payment = Invoke-RestMethod -Method POST `
            -Uri "$base/internal/pos/orders/$orderId/init-payment" `
            -Headers $headers -Body $paymentBody -TimeoutSec 10

        $result.Status = "✅ SUCCESS"
        $result.Details = "Order: $orderCode | Reference: $($payment.data.paymentReference)"

    } catch {
        $errorMsg = $_.Exception.Message
        if ($_.Exception.Response.StatusCode -eq 500) {
            $result.Status = "❌ FAILED (500)"
            $result.Details = "Internal server error - check credentials/gateway"
        } else {
            $result.Status = "❌ FAILED"
            $result.Details = $errorMsg.Substring(0, [Math]::Min(60, $errorMsg.Length))
        }
    }

    $testResults += $result
}

# Display Results
Write-Host "`n╔════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                TEST RESULTS              ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════╝" -ForegroundColor Cyan

$testResults | ForEach-Object {
    $statusColor = if ($_.Status -like "*SUCCESS*") { "Green" } else { "Red" }
    Write-Host "`n$($_.Method)" -ForegroundColor Cyan
    Write-Host "  Status: $($_.Status)" -ForegroundColor $statusColor
    Write-Host "  Details: $($_.Details)" -ForegroundColor Gray
}

# Summary
$successCount = ($testResults | Where-Object { $_.Status -like "*SUCCESS*" }).Count
$totalCount = $testResults.Count

Write-Host "`n╔════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                 SUMMARY                   ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host "Passed: $successCount / $totalCount" -ForegroundColor $(if ($successCount -eq 3) { "Green" } else { "Yellow" })

if ($successCount -eq 1) {
    Write-Host "`n⚠️  Only VNPAY is working. For production:" -ForegroundColor Yellow
    Write-Host "   1. Verify MOMO and ZaloPay credentials in .env" -ForegroundColor Gray
    Write-Host "   2. Ensure merchant accounts are activated" -ForegroundColor Gray
    Write-Host "   3. Check IP whitelisting in gateway dashboards" -ForegroundColor Gray
}

Write-Host "`nTest completed at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n" -ForegroundColor Gray

