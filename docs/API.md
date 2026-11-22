# REST API (qua API Gateway `http://localhost:8080`)

Xác thực: `Authorization: Bearer <accessToken>` (lấy từ `/api/auth/login`). Với SSE dùng `?access_token=<token>`.
Lỗi trả về dạng RFC 7807 `application/problem+json`: `{ "status": 400, "detail": "...", "errors": { "field": "..." } }`.
Danh sách phân trang: `{ content, page, size, totalElements, totalPages }`.

## Auth – `auth-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/auth/register` | công khai | `{email, password, fullName}` → token |
| POST | `/api/auth/login` | công khai | `{email, password}` → `{accessToken, expiresIn, user}` |
| POST | `/api/auth/logout` | công khai | Client xóa token (JWT stateless) |
| GET | `/api/auth/me` | đăng nhập | Thông tin tài khoản |
| GET | `/api/auth/users?q=&page=` | ADMIN | Danh sách tài khoản |
| PUT | `/api/auth/users/{id}/roles` | ADMIN | `{roles: ["CUSTOMER","ANALYST"]}` |
| PUT | `/api/auth/users/{id}/status` | ADMIN | `{enabled: false}` khóa tài khoản |

## User – `user-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET / PUT | `/api/users/me` | đăng nhập | Hồ sơ `{fullName, phone, dateOfBirth, avatarUrl}` |
| POST | `/api/users/me/addresses` | đăng nhập | `{label, recipient, phone, line, city, makeDefault}` |
| PUT / DELETE | `/api/users/me/addresses/{id}` | đăng nhập | Sửa / xóa địa chỉ |
| GET | `/api/users` | ADMIN | Danh sách hồ sơ |

## Product – `product-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/products?q=&categoryId=&minPrice=&maxPrice=&sort=price_asc\|price_desc\|name&page=&size=` | công khai | Tìm kiếm / lọc |
| GET | `/api/products/{id}` | công khai | Chi tiết (cache Redis) |
| POST | `/api/products` | ADMIN, PRODUCT_MANAGER | `{sku, name, description, price, categoryId, imageUrl, images[], initialStock}` |
| PUT | `/api/products/{id}` | ADMIN, PRODUCT_MANAGER | Cập nhật (phát ProductUpdated) |
| DELETE | `/api/products/{id}` | ADMIN, PRODUCT_MANAGER | Ngừng kinh doanh (ProductDiscontinued) |
| POST | `/api/products/{id}/activate` | ADMIN, PRODUCT_MANAGER | Mở bán lại |
| GET | `/api/categories` | công khai | Danh mục (cache Redis) |
| POST / PUT / DELETE | `/api/categories[/{id}]` | ADMIN, PRODUCT_MANAGER | `{name, slug, description}` |

## Cart – `cart-service`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/cart` | Giỏ hàng hiện tại (giá theo read-model sản phẩm) |
| POST | `/api/cart/items` | `{productId, quantity}` |
| PUT | `/api/cart/items/{productId}` | `{quantity}` (0 = xóa) |
| DELETE | `/api/cart/items/{productId}` | Xóa một sản phẩm |
| DELETE | `/api/cart` | Xóa toàn bộ giỏ |

## Order – `order-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/orders` | đăng nhập | `{items:[{productId, quantity}], shippingAddress, paymentMethod: MOCK_CARD\|MOCK_WALLET\|MOCK_FAIL}` → **202** + đơn `PENDING` |
| GET | `/api/orders/my?page=` | đăng nhập | Đơn của tôi |
| GET | `/api/orders/{id}` | chủ đơn / nhân viên | Chi tiết + lịch sử trạng thái |
| POST | `/api/orders/{id}/cancel` | chủ đơn | Hủy (trước khi giao) |
| GET | `/api/orders?status=&page=` | ADMIN, WAREHOUSE_MANAGER, ANALYST | Tất cả đơn |
| PUT | `/api/orders/{id}/status` | ADMIN, WAREHOUSE_MANAGER | `{status: SHIPPING\|DELIVERED\|CANCELLED, note}` |

## Inventory – `inventory-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/inventory/availability?productIds=1,2,3` | công khai | Số lượng khả dụng |
| GET | `/api/inventory?lowStock=true&page=` | nhân viên | Tồn kho |
| GET | `/api/inventory/{productId}/transactions` | nhân viên | Lịch sử biến động |
| POST | `/api/inventory/{productId}/restock` | ADMIN, WAREHOUSE_MANAGER | `{quantity, note}` |
| PUT | `/api/inventory/{productId}/threshold` | ADMIN, WAREHOUSE_MANAGER | `{threshold}` |

## Payment – `payment-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/payments?status=&page=` | ADMIN, ANALYST | Danh sách giao dịch |
| GET | `/api/payments/summary` | ADMIN, ANALYST | Số lượng / số tiền theo trạng thái |
| GET | `/api/payments/order/{orderId}` | chủ đơn / ADMIN | Payment + các lần charge/refund |

## Notification – `notification-service`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/notifications/stream` | **SSE**: event `notification` |
| GET | `/api/notifications?page=` | Thông báo của tôi + thông báo theo vai trò |
| GET | `/api/notifications/unread-count` | `{count}` |
| POST | `/api/notifications/{id}/read`, `/api/notifications/read-all` | Đánh dấu đã đọc |

## Analytics – `analytics-service` (nhân viên)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/analytics/snapshot` | KPI, chuỗi thời gian, trạng thái đơn, top sản phẩm, tồn kho thấp, cảnh báo, chỉ số Kafka |
| GET | `/api/analytics/stream` | **SSE**: `snapshot` (≤ 1 lần/giây), `alert` (ngay lập tức) |
| GET | `/api/analytics/reports/sales?window=1m\|5m\|1h&hours=24` | Báo cáo doanh thu từ `sales_aggregates` (ADMIN, ANALYST) |
| GET | `/api/analytics/reports/products?limit=20` | Doanh số theo sản phẩm |
| GET | `/api/analytics/reports/alerts?limit=100` | Lịch sử cảnh báo |

## Simulator – `simulator-service` (ADMIN)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/simulator` | Trạng thái, thống kê, hoạt động gần đây |
| POST | `/api/simulator/start`, `/api/simulator/stop` | Bật / tắt khách hàng ảo |
| PUT | `/api/simulator/settings` | `{sessionsPerMinute, maxConcurrentSessions, browseOnlyRate, cartAbandonRate, paymentFailureRate, cancelRate, newCustomerRate, wave, autoRestock, autoFulfill}` |
| POST | `/api/simulator/burst` | `{orders: 60, seconds: 30}` – mua dồn dập |
| POST | `/api/simulator/reset-stats` | Đặt lại thống kê |

## Ví dụ với curl

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer@shop.local","password":"Customer@123"}' | jq -r .accessToken)

curl -s "localhost:8080/api/products?q=tai%20nghe" | jq '.content[] | {id, name, price}'

curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":8,"quantity":1}],"shippingAddress":"1 Lê Lợi, Q1, TP.HCM","paymentMethod":"MOCK_CARD"}'

curl -N "localhost:8080/api/notifications/stream?access_token=$TOKEN"   # theo dõi realtime
```
