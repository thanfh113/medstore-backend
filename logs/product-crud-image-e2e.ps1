$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8080/api/v1"
Write-Output "STEP 0: health"
curl.exe -s -o NUL -w "HTTP %{http_code}`n" "http://127.0.0.1:8080/"
$img1 = Join-Path $PSScriptRoot "product-crud-img-1.png"
$img2 = Join-Path $PSScriptRoot "product-crud-img-2.png"
[IO.File]::WriteAllBytes($img1,[Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7+8n8AAAAASUVORK5CYII="))
[IO.File]::WriteAllBytes($img2,[Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7+8n8AAAAASUVORK5CYII="))
$loginReq = @{ credential = "admin@medstore.vn"; password = "Admin@123" }
$loginBody = ConvertTo-Json -InputObject $loginReq -Compress
$login = Invoke-RestMethod -Method POST -Uri "$base/auth/login" -ContentType "application/json" -Body $loginBody
$token = $login.accessToken
if([string]::IsNullOrWhiteSpace($token)){ throw "No accessToken" }
Write-Output "STEP 1: login PASS"
$up1Raw = curl.exe -s -X POST "$base/upload?type=PRODUCT_IMAGE" -H "Authorization: Bearer $token" -F "file=@$img1"
$up1Text = ($up1Raw -join "")
$up1Obj = ConvertFrom-Json -InputObject $up1Text
if([string]::IsNullOrWhiteSpace($up1Obj.url) -or [string]::IsNullOrWhiteSpace($up1Obj.publicId)){ throw "Upload1 failed: $up1Text" }
Write-Output ("STEP 2: upload1 PASS publicId=" + $up1Obj.publicId)
$name = "Desktop CRUD Image E2E " + [Guid]::NewGuid().ToString("N").Substring(0,8)
$createReq = @{
  categoryId = "cat-supplies"
  name = $name
  description = "Create with image"
  brand = "Desktop QA"
  origin = "Viet Nam"
  unit = "Hop"
  price = 123456.0
  originalPrice = 150000.0
  discountPct = 0
  rewardPoints = 0
  productType = "MEDICAL_SUPPLY"
  registrationNumber = "E2E-IMG-01"
  riskClassification = "A"
  requiresCertification = $true
  requiresConsultation = $false
  isActive = $true
  attributes = @{}
  images = @(@{ url = $up1Obj.url; publicId = $up1Obj.publicId; sortOrder = 0 })
}
$createBody = ConvertTo-Json -InputObject $createReq -Depth 8 -Compress
$create = Invoke-RestMethod -Method POST -Uri "$base/products" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $createBody
$productId = $create.data.id
if([string]::IsNullOrWhiteSpace($productId)){ throw "Create product missing id" }
Write-Output ("STEP 3: create PASS productId=" + $productId)
$detail1 = Invoke-RestMethod -Method GET -Uri "$base/products/$productId"
if($detail1.data.images.Count -lt 1){ throw "No image in detail after create" }
if($detail1.data.images[0].publicId -ne $up1Obj.publicId){ throw "Detail after create image mismatch" }
Write-Output "STEP 4: verify detail after create PASS"
$up2Raw = curl.exe -s -X POST "$base/upload?type=PRODUCT_IMAGE" -H "Authorization: Bearer $token" -F "file=@$img2"
$up2Text = ($up2Raw -join "")
$up2Obj = ConvertFrom-Json -InputObject $up2Text
if([string]::IsNullOrWhiteSpace($up2Obj.url) -or [string]::IsNullOrWhiteSpace($up2Obj.publicId)){ throw "Upload2 failed: $up2Text" }
Write-Output ("STEP 5: upload2 PASS publicId=" + $up2Obj.publicId)
$updateReq = @{ name = ($name + " Updated"); price = 135000.0; isActive = $false; images = @(@{ url = $up2Obj.url; publicId = $up2Obj.publicId; sortOrder = 0 }) }
$updateBody = ConvertTo-Json -InputObject $updateReq -Depth 8 -Compress
Invoke-RestMethod -Method PUT -Uri "$base/products/$productId" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $updateBody | Out-Null
Write-Output "STEP 6: update PASS"
$detail2 = Invoke-RestMethod -Method GET -Uri "$base/products/$productId"
if($detail2.data.images.Count -lt 1){ throw "No image in detail after update" }
if($detail2.data.images[0].publicId -ne $up2Obj.publicId){ throw "Detail after update image mismatch" }
if($detail2.data.images[0].publicId -eq $up1Obj.publicId){ throw "Old image still present after update" }
Write-Output "STEP 7: verify detail after update PASS"
$internal = Invoke-RestMethod -Method GET -Uri "$base/internal/products?limit=200" -Headers @{ Authorization = "Bearer $token" }
$found = $null
foreach($p in $internal.data){ if($p.id -eq $productId){ $found = $p; break } }
if($null -eq $found){ throw "Product not found in internal list after update" }
Write-Output ("STEP 8: internal list PASS imagePublicId=" + $found.images[0].publicId)
Invoke-RestMethod -Method DELETE -Uri "$base/products/$productId" -Headers @{ Authorization = "Bearer $token" } | Out-Null
Write-Output "STEP 9: delete PASS"
try {
  Invoke-WebRequest -Method GET -Uri "$base/products/$productId" -UseBasicParsing -TimeoutSec 10 | Out-Null
  throw "Expected 404 after delete but got success"
} catch {
  if($_.Exception.Response){
    $status = [int]$_.Exception.Response.StatusCode
    if($status -ne 404){ throw "Expected 404 after delete, got $status" }
  } else {
    throw $_
  }
}
Write-Output "STEP 10: verify 404 after delete PASS"
Write-Output "E2E RESULT: PASS"
