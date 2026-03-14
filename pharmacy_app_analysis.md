# 📋 Phân Tích Chức Năng & Thiết Kế Backend/DB – App Nhà Thuốc

## 1. Tổng Quan Chức Năng (từ UI)

### Bottom Navigation (5 tab)
| Tab | Màn hình | Chức năng chính |
|-----|----------|-----------------|
| Trang chủ | HomeScreen | Feed sản phẩm, flash sale, best seller, category |
| Điểm thưởng | RewardScreen | Xem điểm, đổi quà, lịch sử đổi điểm |
| Tư vấn | ConsultBottomSheet → ChatScreen | Chat với dược sĩ, gọi tổng đài |
| Giỏ hàng | CartScreen | Quản lý giỏ, apply mã, dùng điểm, checkout |
| Tài khoản | AccountScreen | Hồ sơ, đơn hàng, địa chỉ, thanh toán |

### Màn hình phụ
| Màn hình | Chức năng |
|----------|-----------|
| ProductDetailScreen | Chi tiết SP, slider ảnh, đánh giá, thêm giỏ |
| CategoryProductScreen | Danh sách SP theo danh mục |
| MyOrdersScreen | Lịch sử đơn, filter theo trạng thái, tìm kiếm |
| OrderDetailScreen | Chi tiết đơn, hỗ trợ, mua lại |
| FindPharmacyScreen | Bản đồ, tìm nhà thuốc gần, gọi/chỉ đường |
| VaccineScreen | Đặt lịch tiêm chủng, điền hồ sơ |
| BuyMedicineScreen | Mua thuốc theo đơn (upload đơn thuốc) |
| NotificationScreen | Thông báo hệ thống, khuyến mãi |
| ChatScreen | Chat real-time với dược sĩ, gửi ảnh |

---

## 2. Ba Role & Quyền Hạn

```
┌─────────────────────────────────────────────────────────┐
│                      ADMIN                              │
│  • Quản lý toàn bộ hệ thống                            │
│  • Quản lý shops/pharmacies, duyệt đăng ký              │
│  • Xem dashboard thống kê tổng hợp                     │
│  • Quản lý người dùng (ban/unban)                       │
│  • Cấu hình chương trình điểm thưởng                   │
│  • Quản lý banner quảng cáo, tin tức sức khỏe          │
│  • Duyệt/quản lý danh mục sản phẩm                     │
├─────────────────────────────────────────────────────────┤
│                   SHOP (Nhà Thuốc)                      │
│  • Quản lý sản phẩm của shop (CRUD)                    │
│  • Xử lý đơn hàng (xác nhận, giao, huỷ)               │
│  • Quản lý kho hàng, tồn kho                           │
│  • Chat tư vấn với khách hàng                          │
│  • Xem doanh thu của shop                              │
│  • Quản lý chi nhánh, giờ mở cửa                       │
│  • Quản lý lịch tiêm vắc-xin                           │
├─────────────────────────────────────────────────────────┤
│                      USER                               │
│  • Đăng ký, đăng nhập, cập nhật hồ sơ                 │
│  • Tìm kiếm & xem sản phẩm                             │
│  • Thêm giỏ hàng, đặt hàng, thanh toán                 │
│  • Xem lịch sử đơn hàng, đổi/trả                      │
│  • Tích điểm, đổi quà điểm thưởng                     │
│  • Chat với dược sĩ, tư vấn                            │
│  • Đặt lịch tiêm vắc-xin                              │
│  • Upload đơn thuốc (buy by prescription)              │
│  • Đánh giá, bình luận sản phẩm                       │
│  • Quản lý địa chỉ, phương thức thanh toán             │
│  • Xem/lưu thông báo                                   │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Thiết Kế Database (PostgreSQL)

### 3.1 Bảng Users & Auth

```sql
-- Bảng người dùng (gộp 3 role)
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       VARCHAR(15) UNIQUE NOT NULL,
    email       VARCHAR(255) UNIQUE,
    password    VARCHAR(255) NOT NULL,           -- hashed bcrypt
    full_name   VARCHAR(100),
    avatar_url  TEXT,
    gender      VARCHAR(10),                     -- Nam/Nữ/Khác
    date_of_birth DATE,
    role        VARCHAR(20) NOT NULL DEFAULT 'USER', -- ADMIN | SHOP | USER
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- Token refresh
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW()
);
```

### 3.2 Bảng Shop (Nhà Thuốc)

```sql
-- Thông tin shop/nhà thuốc
CREATE TABLE shops (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID REFERENCES users(id),     -- user có role=SHOP
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    logo_url        TEXT,
    license_number  VARCHAR(100),                  -- Giấy phép kinh doanh
    is_approved     BOOLEAN DEFAULT FALSE,         -- Admin duyệt
    created_at      TIMESTAMP DEFAULT NOW()
);

-- Chi nhánh nhà thuốc
CREATE TABLE pharmacy_branches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id         UUID REFERENCES shops(id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    address         TEXT NOT NULL,
    latitude        DECIMAL(10, 8),
    longitude       DECIMAL(11, 8),
    phone           VARCHAR(15),
    open_time       TIME,
    close_time      TIME,
    is_open_now     BOOLEAN DEFAULT FALSE,         -- computed/cached
    is_active       BOOLEAN DEFAULT TRUE
);
```

### 3.3 Bảng Sản Phẩm

```sql
-- Danh mục
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES categories(id),
    name        VARCHAR(100) NOT NULL,
    icon_url    TEXT,
    sort_order  INT DEFAULT 0,
    is_active   BOOLEAN DEFAULT TRUE
);

-- Sản phẩm
CREATE TABLE products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id              UUID REFERENCES shops(id),
    category_id          UUID REFERENCES categories(id),
    name                 VARCHAR(300) NOT NULL,
    slug                 VARCHAR(300) UNIQUE,
    description          TEXT,
    brand                VARCHAR(100),
    origin               VARCHAR(100),                  -- Việt Nam, Hàn Quốc...
    sku                  VARCHAR(100) UNIQUE,
    unit                 VARCHAR(50) DEFAULT 'Hộp',    -- Hộp/Viên/Chai/Gói
    price                DECIMAL(12,2) NOT NULL,
    original_price       DECIMAL(12,2),
    discount_pct         INT DEFAULT 0,
    reward_points        INT DEFAULT 0,
    stock                INT DEFAULT 0,
    -- Phân loại sản phẩm (theo luật Dược Việt Nam)
    product_type         VARCHAR(30) DEFAULT 'MEDICINE', -- MEDICINE | SUPPLEMENT | COSMETIC | DEVICE | FOOD | OTHER
    -- Số đăng ký lưu hành (ví dụ: VD-12345-16, GPSP-123/2023)
    registration_number  VARCHAR(100),
    is_prescription      BOOLEAN DEFAULT FALSE,         -- Thuốc kê đơn
    requires_consultation BOOLEAN DEFAULT FALSE,        -- Cần DS tư vấn trước khi mua
    is_active            BOOLEAN DEFAULT TRUE,
    is_flash_sale        BOOLEAN DEFAULT FALSE,
    flash_sale_end       TIMESTAMP,
    created_at           TIMESTAMP DEFAULT NOW(),
    updated_at           TIMESTAMP DEFAULT NOW()
);

-- Ảnh sản phẩm
CREATE TABLE product_images (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID REFERENCES products(id) ON DELETE CASCADE,
    url         TEXT NOT NULL,
    sort_order  INT DEFAULT 0
);

-- Giấy tờ & Chứng nhận sản phẩm
CREATE TABLE product_certificates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID REFERENCES products(id) ON DELETE CASCADE,
    -- REGISTRATION (số ĐK lưu hành) | IMPORT_LICENSE (giấy phép NK) | COA | GMP | OTHER
    type        VARCHAR(30) DEFAULT 'REGISTRATION',
    name        VARCHAR(200) NOT NULL,    -- Tên giấy tờ / số hiệu
    file_url    TEXT NOT NULL,            -- URL ảnh hoặc PDF
    issued_by   VARCHAR(200),             -- Cơ quan cấp
    issued_at   VARCHAR(20),              -- Ngày cấp (yyyy-MM-dd)
    expires_at  VARCHAR(20),              -- Ngày hết hạn
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Bệnh lý / Công dụng (cho SeasonalDisease section)
CREATE TABLE disease_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,             -- Tim mạch, Hô hấp...
    icon_url    TEXT
);

CREATE TABLE product_diseases (
    product_id      UUID REFERENCES products(id),
    disease_id      UUID REFERENCES disease_categories(id),
    PRIMARY KEY (product_id, disease_id)
);
```

### 3.4 Giỏ Hàng & Đơn Hàng

```sql
-- Giỏ hàng
CREATE TABLE cart_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    product_id  UUID REFERENCES products(id),
    quantity    INT NOT NULL DEFAULT 1,
    unit        VARCHAR(50) DEFAULT 'Hộp',
    created_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, product_id)
);

-- Địa chỉ giao hàng
CREATE TABLE user_addresses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id) ON DELETE CASCADE,
    label           VARCHAR(50),                   -- Nhà, Cơ quan
    recipient_name  VARCHAR(100),
    phone           VARCHAR(15),
    address         TEXT NOT NULL,
    ward            VARCHAR(100),
    district        VARCHAR(100),
    province        VARCHAR(100),
    is_default      BOOLEAN DEFAULT FALSE
);

-- Đơn hàng
CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_code      VARCHAR(20) UNIQUE NOT NULL,   -- #4497026
    user_id         UUID REFERENCES users(id),
    shop_id         UUID REFERENCES shops(id),
    address_id      UUID REFERENCES user_addresses(id),
    status          VARCHAR(30) DEFAULT 'PENDING',
    -- PENDING | PROCESSING | SHIPPING | DELIVERED | CANCELLED | RETURNED
    pickup_type     VARCHAR(30) DEFAULT 'DELIVERY', -- DELIVERY | PICKUP
    branch_id       UUID REFERENCES pharmacy_branches(id),
    subtotal        DECIMAL(12,2),
    shipping_fee    DECIMAL(12,2) DEFAULT 0,
    discount        DECIMAL(12,2) DEFAULT 0,
    points_used     INT DEFAULT 0,
    points_earned   INT DEFAULT 0,
    total           DECIMAL(12,2),
    payment_method  VARCHAR(30),                   -- COD | MOMO | CARD | TRANSFER
    payment_status  VARCHAR(20) DEFAULT 'UNPAID',
    note            TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Chi tiết đơn hàng
CREATE TABLE order_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID REFERENCES orders(id) ON DELETE CASCADE,
    product_id  UUID REFERENCES products(id),
    name        VARCHAR(300),          -- snapshot tên tại thời điểm đặt
    price       DECIMAL(12,2),
    quantity    INT,
    unit        VARCHAR(50)
);

-- Đơn thuốc theo kê đơn (BuyMedicineScreen)
CREATE TABLE prescriptions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    order_id    UUID REFERENCES orders(id),
    image_url   TEXT NOT NULL,
    note        TEXT,
    status      VARCHAR(20) DEFAULT 'PENDING', -- PENDING | VERIFIED | REJECTED
    created_at  TIMESTAMP DEFAULT NOW()
);
```

### 3.5 Điểm Thưởng & Đổi Quà

```sql
-- Tài khoản điểm thưởng
CREATE TABLE reward_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE REFERENCES users(id),
    total_points    INT DEFAULT 0,
    used_points     INT DEFAULT 0,
    available_pts   INT GENERATED ALWAYS AS (total_points - used_points) STORED
);

-- Lịch sử điểm
CREATE TABLE reward_transactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    order_id    UUID REFERENCES orders(id),
    type        VARCHAR(20),   -- EARN | REDEEM | EXPIRE | ADJUST
    points      INT NOT NULL,  -- >0 = cộng, <0 = trừ
    description TEXT,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Sản phẩm đổi quà
CREATE TABLE reward_products (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(300),
    image_url   TEXT,
    point_cost  INT NOT NULL,
    price_text  VARCHAR(50),
    stock       INT DEFAULT 0,
    is_active   BOOLEAN DEFAULT TRUE
);

-- Lịch sử đổi quà
CREATE TABLE reward_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id),
    reward_product_id UUID REFERENCES reward_products(id),
    quantity        INT DEFAULT 1,
    points_used     INT,
    status          VARCHAR(20) DEFAULT 'PROCESSING',
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### 3.6 Vắc-xin & Lịch Tiêm

```sql
CREATE TABLE vaccines (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id     UUID REFERENCES shops(id),
    name        VARCHAR(200),
    description TEXT,
    price       DECIMAL(12,2),
    manufacturer VARCHAR(100),
    age_range   VARCHAR(100)
);

CREATE TABLE vaccine_bookings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id),
    vaccine_id      UUID REFERENCES vaccines(id),
    branch_id       UUID REFERENCES pharmacy_branches(id),
    patient_name    VARCHAR(100),
    gender          VARCHAR(10),
    date_of_birth   DATE,
    appointment_dt  TIMESTAMP,
    status          VARCHAR(20) DEFAULT 'PENDING',
    -- PENDING | CONFIRMED | DONE | CANCELLED
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### 3.7 Chat & Tư Vấn

```sql
CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    shop_id     UUID REFERENCES shops(id),
    product_id  UUID REFERENCES products(id),  -- context sản phẩm nếu có
    status      VARCHAR(20) DEFAULT 'OPEN',    -- OPEN | CLOSED
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    sender_id   UUID REFERENCES users(id),
    content     TEXT,
    type        VARCHAR(20) DEFAULT 'TEXT',    -- TEXT | IMAGE | PRODUCT_CARD
    metadata    JSONB,                          -- product card data, image url...
    created_at  TIMESTAMP DEFAULT NOW()
);
```

### 3.8 Đánh Giá & Thông Báo

```sql
CREATE TABLE reviews (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID REFERENCES products(id),
    user_id     UUID REFERENCES users(id),
    order_id    UUID REFERENCES orders(id),
    rating      SMALLINT CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(order_id, product_id)
);

CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id),
    title       VARCHAR(200),
    body        TEXT,
    type        VARCHAR(30),   -- ORDER_STATUS | PROMOTION | CHAT | REWARD | SYSTEM
    ref_id      UUID,          -- order_id hoặc product_id liên quan
    is_read     BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- Banner quảng cáo (PromoBannerPager)
CREATE TABLE banners (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    image_url   TEXT NOT NULL,
    link_url    TEXT,
    title       VARCHAR(200),
    sort_order  INT DEFAULT 0,
    is_active   BOOLEAN DEFAULT TRUE,
    start_dt    TIMESTAMP,
    end_dt      TIMESTAMP
);

-- Tin tức sức khoẻ (HealthNewsSection)
CREATE TABLE health_articles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(300),
    content     TEXT,
    thumbnail_url TEXT,
    author      VARCHAR(100),
    is_published BOOLEAN DEFAULT FALSE,
    published_at TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW()
);
```

### 3.9 Phương Thức Thanh Toán

```sql
CREATE TABLE payment_methods (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(20),       -- MOMO | CARD | BANK_TRANSFER
    label       VARCHAR(100),
    last4       VARCHAR(4),        -- 4 số cuối thẻ
    is_default  BOOLEAN DEFAULT FALSE
);

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID REFERENCES orders(id),
    method          VARCHAR(20),
    amount          DECIMAL(12,2),
    transaction_id  VARCHAR(200),  -- mã từ cổng thanh toán
    status          VARCHAR(20) DEFAULT 'PENDING',
    paid_at         TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

## 4. Kiến Trúc Hệ Thống

### 4.1 Ba Nền Tảng Client

```
┌─────────────────────────────────────────────────────────────┐
│               KOTLIN ECOSYSTEM                              │
│                                                             │
│  📱 Android App         🖥️ Desktop App       🌐 Web Admin  │
│  (Jetpack Compose)      (Compose Desktop)     (Next.js)    │
│  ── USER ──             ── SHOP ──            ── ADMIN ──  │
│                                ▲                           │
│                                │  REST API + WebSocket     │
│                    ┌───────────┴──────────┐                │
│                    │   Ktor Backend       │                │
│                    │   (Kotlin)           │                │
│                    └───────────┬──────────┘                │
│                                │                           │
│                         ┌──────┴──────┐                    │
│                         │   MySQL     │                    │
│                         └─────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Công Nghệ Chi Tiết

| Thành phần | Công nghệ | Lý do |
|-----------|----------|-------|
| **Mobile (User)** | Android + Jetpack Compose | Đã có sẵn |
| **Desktop (Shop)** | Compose for Desktop (KMP) | Cùng Kotlin, share model |
| **Web (Admin)** | Next.js + TypeScript | Dashboard phức tạp, SSR |
| **Backend** | Ktor (Kotlin) | Native Coroutines, cùng hệ sinh thái |
| **Database** | MySQL 8.x | Phổ biến, dễ deploy |
| **ORM** | Exposed (Jetbrains) | ORM Kotlin-native cho Ktor |
| **Auth** | JWT (access 15min + refresh 7d) | Standard, stateless |
| **File Upload** | Firebase Storage hoặc MinIO | Ảnh sản phẩm, đơn thuốc |
| **Real-time Chat** | Ktor WebSockets | Built-in Ktor, không cần thư viện ngoài |
| **Push Notification** | FCM (Firebase Cloud Messaging) | Android push |
| **Maps** | Google Maps API | FindPharmacyScreen |
| **Cache** | Redis | Session, rate limiting |
| **Payment** | VNPay / MoMo SDK | Thanh toán nội địa |

### 4.3 Cấu Trúc Thư Mục Dự Án

```
nhathuoc/                          ← đã có (Android)
nhathuoc-backend/                  ← Ktor Backend
  src/main/kotlin/
    plugins/           Routing, Auth, DB, Serialization
    routes/            authRoutes, productRoutes, orderRoutes...
    models/            Exposed Table definitions
    services/          Business logic
    dto/               Request/Response DTOs
nhathuoc-desktop/                  ← Compose Desktop (Shop)
nhathuoc-admin/                    ← Next.js Web (Admin)
  app/
    dashboard/         Thống kê doanh thu
    shops/             Duyệt nhà thuốc
    products/          Quản lý sản phẩm
    orders/            Quản lý đơn hàng
    users/             Quản lý người dùng
    banners/           Quảng cáo
    finance/           Doanh thu, tài chính
```

### 4.4 Web Admin – Các Tính Năng

| Module | Chức năng |
|--------|----------|
| **Dashboard** | Tổng đơn, doanh thu hôm nay/tuần/tháng, biểu đồ xu hướng |
| **Tài chính** | Doanh thu theo shop, theo thời gian, xuất Excel/PDF |
| **Shops** | Duyệt đăng ký, khoá/mở shop, xem chi nhánh |
| **Sản phẩm** | Duyệt sản phẩm, quản lý danh mục, bệnh lý |
| **Đơn hàng** | Xem toàn bộ đơn, filter theo trạng thái, shop |
| **Người dùng** | Danh sách user, khoá tài khoản, xem điểm thưởng |
| **Banner** | Thêm/sửa/xoá banner quảng cáo |
| **Tin tức** | Viết/duyệt tin sức khoẻ (Health News) |
| **Quà thưởng** | Cấu hình sản phẩm đổi điểm, tỉ lệ tích điểm |

### Cấu Trúc API (REST)

```
/api/v1
  /auth
    POST /register
    POST /login
    POST /refresh-token
    POST /logout

  /users                         [USER, ADMIN]
    GET  /me
    PUT  /me
    GET  /me/addresses
    POST /me/addresses
    PUT  /me/addresses/:id
    DELETE /me/addresses/:id

  /products                      [PUBLIC]
    GET  /               (filter, sort, search, category, page)
    GET  /:id
    GET  /flash-sale
    GET  /best-sellers
    GET  /by-disease/:diseaseId

  /products                      [SHOP, ADMIN]
    POST /              tạo sản phẩm (có trường product_type, registration_number)
    PUT  /:id           sửa sản phẩm
    DELETE /:id         xoá sản phẩm
    GET  /:id/certificates          xem giấy tờ chứng nhận
    POST /:id/certificates          thêm giấy tờ (kèm file upload)
    DELETE /:id/certificates/:cid   xoá giấy tờ

  /categories                    [PUBLIC]
    GET  /
    GET  /:id/products

  /cart                          [USER]
    GET  /
    POST /items
    PUT  /items/:id
    DELETE /items/:id

  /orders                        [USER]
    POST /              đặt hàng
    GET  /              lịch sử đơn
    GET  /:id           chi tiết đơn
    POST /:id/cancel    huỷ đơn
    POST /:id/reorder   mua lại
    POST /prescription  upload đơn thuốc

  /orders                        [SHOP]
    GET  /shop          đơn của shop
    PUT  /:id/status    cập nhật trạng thái đơn

  /rewards                       [USER]
    GET  /              điểm + lịch sử
    GET  /products      danh sách quà đổi
    POST /redeem        đổi quà

  /chat                          [USER, SHOP]
    GET  /sessions
    POST /sessions
    GET  /sessions/:id/messages
    POST /sessions/:id/messages
    (WS) socket.io namespace /chat

  /pharmacies                    [PUBLIC]
    GET  /              tìm nhà thuốc gần (lat, lng)
    GET  /:id

  /vaccines                      [USER, SHOP]
    GET  /              danh sách vắc-xin
    POST /bookings      đặt lịch tiêm
    GET  /bookings/me   lịch của tôi

  /notifications                 [USER]
    GET  /
    PUT  /:id/read
    PUT  /read-all

  /banners                       [PUBLIC]
    GET  /

  /health-articles               [PUBLIC]
    GET  /

  /admin                         [ADMIN ONLY]
    /users          quản lý users
    /shops          duyệt/quản lý shops
    /products       kiểm duyệt sản phẩm
    /orders         xem tất cả đơn hàng
    /analytics      dashboard thống kê
    /banners        quản lý banner
    /rewards        cấu hình chương trình điểm
```

---

## 5. Tóm Tắt Chức Năng Theo Role

### 👑 Admin
- [ ] Đăng nhập (web dashboard riêng)
- [ ] Duyệt đăng ký nhà thuốc (shops)
- [ ] Quản lý người dùng (xem, ban, phân quyền)
- [ ] Duyệt danh mục sản phẩm
- [ ] Quản lý banner quảng cáo
- [ ] Quản lý tin tức sức khoẻ
- [ ] Cấu hình chương trình điểm thưởng
- [ ] Dashboard: doanh thu, đơn hàng, người dùng
- [ ] Xem toàn bộ đơn hàng hệ thống

### 🏪 Shop (Nhà Thuốc)
- [ ] Đăng ký, chờ Admin duyệt
- [ ] Quản lý sản phẩm (thêm/sửa/xoá, hình ảnh, tồn kho)
- [ ] Xử lý đơn hàng (xác nhận → đang giao → đã giao)
- [ ] Quản lý chi nhánh & giờ mở cửa
- [ ] Chat tư vấn khách hàng (nhận & trả lời)
- [ ] Quản lý lịch tiêm vắc-xin
- [ ] Xem doanh thu của shop

### 👤 User (Khách Hàng)
- [ ] Đăng ký / Đăng nhập (SĐT + OTP)
- [ ] Cập nhật hồ sơ cá nhân
- [ ] Tìm kiếm sản phẩm (text, voice, camera)
- [ ] Xem chi tiết sản phẩm, đánh giá
- [ ] Thêm giỏ hàng, thay đổi số lượng/đơn vị
- [ ] Áp dụng mã ưu đãi / dùng điểm
- [ ] Đặt hàng (giao tận nơi / nhận tại cửa hàng)
- [ ] Upload đơn thuốc (thuốc kê đơn)
- [ ] Xem và theo dõi trạng thái đơn hàng
- [ ] Đổi trả hàng
- [ ] Viết đánh giá sản phẩm
- [ ] Chat với dược sĩ tư vấn
- [ ] Tích điểm & đổi quà
- [ ] Đặt lịch tiêm vắc-xin
- [ ] Tìm nhà thuốc gần nhất (Maps)
- [ ] Quản lý địa chỉ giao hàng
- [ ] Quản lý phương thức thanh toán
- [ ] Xem thông báo
- [ ] Quét mã QR (xem hồ sơ / kiểm tra hàng chính hãng)
