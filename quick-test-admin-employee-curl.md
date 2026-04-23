# Quick Test Admin Employee

Thay các biến dưới đây trước khi chạy:

```powershell
$BASE_URL = "http://localhost:8080/api/v1"
$ADMIN_EMAIL = "admin@medstore.vn"
$ADMIN_PASSWORD = "MAT_KHAU_ADMIN"
$EMPLOYEE_EMAIL = "employee@medstore.vn"
$EMPLOYEE_PASSWORD = "MAT_KHAU_EMPLOYEE"
```

## 1. Login admin

```powershell
$adminLoginBody = @{
  credential = $ADMIN_EMAIL
  password   = $ADMIN_PASSWORD
} | ConvertTo-Json

$adminLogin = Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/auth/login" `
  -ContentType "application/json" `
  -Body $adminLoginBody

$adminLogin | ConvertTo-Json -Depth 10
$ADMIN_TOKEN = $adminLogin.accessToken
```

Kỳ vọng:
- có `accessToken`
- `user.role = ADMIN`

## 2. Login employee

```powershell
$employeeLoginBody = @{
  credential = $EMPLOYEE_EMAIL
  password   = $EMPLOYEE_PASSWORD
} | ConvertTo-Json

$employeeLogin = Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/auth/login" `
  -ContentType "application/json" `
  -Body $employeeLoginBody

$employeeLogin | ConvertTo-Json -Depth 10
$EMPLOYEE_TOKEN = $employeeLogin.accessToken
```

Kỳ vọng:
- có `accessToken`
- `user.role = EMPLOYEE`

## 3. Internal dashboard

### Admin

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/internal/dashboard" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } | ConvertTo-Json -Depth 10
```

### Employee

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/internal/dashboard" `
  -Headers @{ Authorization = "Bearer $EMPLOYEE_TOKEN" } | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- trả `data.totalRevenue`
- trả `data.totalOrders`
- không lỗi `No managed shop found for this user`

## 4. Internal orders

### List orders as admin

```powershell
$adminOrders = Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/internal/orders" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" }

$adminOrders | ConvertTo-Json -Depth 10
```

### List orders as employee

```powershell
$employeeOrders = Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/internal/orders" `
  -Headers @{ Authorization = "Bearer $EMPLOYEE_TOKEN" }

$employeeOrders | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- trả `data` là danh sách đơn
- không lỗi quyền

## 5. Internal order detail

Lấy 1 order id từ kết quả bước 4:

```powershell
$ORDER_ID = $adminOrders.data[0].id
```

### Detail as admin

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/internal/orders/$ORDER_ID" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } | ConvertTo-Json -Depth 10
```

### Update status as admin

```powershell
$updateOrderBody = @{
  status = "PROCESSING"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/internal/orders/$ORDER_ID/status" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } `
  -ContentType "application/json" `
  -Body $updateOrderBody | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- detail trả đúng đơn thuộc `shop-001`
- update status thành công

## 6. Inventory

### List batches as admin

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/inventory/batches" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } | ConvertTo-Json -Depth 10
```

### Expiring alerts as employee

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/inventory/alerts/expiring?days=30" `
  -Headers @{ Authorization = "Bearer $EMPLOYEE_TOKEN" } | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- cả admin và employee đều gọi được
- shop context lấy từ `shop_staff`

## 7. Products

### Public list

```powershell
Invoke-RestMethod `
  -Method GET `
  -Uri "$BASE_URL/products" | ConvertTo-Json -Depth 10
```

### Internal create product as admin

```powershell
$createProductBody = @{
  name = "Sản phẩm test nội bộ"
  categoryId = "cat-monitor"
  brand = "TEST"
  origin = "Viet Nam"
  sku = "TEST-SKU-001"
  unit = "Cái"
  price = 100000
  originalPrice = 120000
  discountPct = 0
  rewardPoints = 0
  productType = "DEVICE"
  riskClassification = "A"
  requiresCertification = $false
  requiresConsultation = $false
  isActive = $true
  attributes = @{}
} | ConvertTo-Json

Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/products" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } `
  -ContentType "application/json" `
  -Body $createProductBody | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- public products chạy bình thường
- admin tạo product thành công

## 8. Refresh token

```powershell
$refreshBody = @{
  refreshToken = $adminLogin.refreshToken
} | ConvertTo-Json

Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/auth/refresh" `
  -ContentType "application/json" `
  -Body $refreshBody | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- trả `accessToken` mới
- trả `refreshToken` mới

## 9. Logout

```powershell
Invoke-RestMethod `
  -Method POST `
  -Uri "$BASE_URL/auth/logout" `
  -Headers @{ Authorization = "Bearer $ADMIN_TOKEN" } | ConvertTo-Json -Depth 10
```

Kỳ vọng:
- trả message logout thành công

## Điều kiện để sang pha 2 DB

Chỉ xóa `shops.owner_id` khi các bước sau đều pass:
- login admin
- login employee
- internal dashboard
- internal orders
- inventory
- desktop products/orders/dashboard chạy bình thường
