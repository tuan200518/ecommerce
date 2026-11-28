# REST API (qua API Gateway `http://localhost:8080`)

Xác thực: `Authorization: Bearer <accessToken>` (lấy từ `/api/auth/login`). Với SSE dùng `?access_token=<token>`.
Mọi response có header `X-Correlation-Id` (xem cuối trang). Lỗi trả về dạng RFC 7807 `application/problem+json`: `{ "status": 400, "detail": "...", "errors": { "field": "..." } }`.
Danh sách phân trang: `{ content, page, size, totalElements, totalPages }`.

**Rate limit** (Gateway, cửa sổ 1 phút, đếm qua Redis): đăng nhập 300/IP, đăng ký 100/IP, `POST /api/orders` 20/người dùng, `POST /api/promotions/quote` 60/người dùng, còn lại 3000/người dùng (hoặc /IP nếu chưa đăng nhập). Mọi response có `X-RateLimit-Limit` / `X-RateLimit-Remaining`; vượt giới hạn → **429** kèm `Retry-After` (giây). Chỉnh bằng biến `RATE_LIMIT_*`, tắt bằng `RATE_LIMIT_ENABLED=false`.

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

Sai mật khẩu 5 lần liên tiếp (`AUTH_MAX_FAILED_LOGINS`) → tài khoản bị **tạm khóa** 5 phút (`AUTH_LOCK_DURATION`): `POST /api/auth/login` trả **423 Locked** kể cả khi nhập đúng mật khẩu. Email không tồn tại và sai mật khẩu trả cùng thông báo 401. Mọi lần đăng nhập thất bại, đổi vai trò, khóa/mở khóa đều được ghi Nhật ký kiểm toán.

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
| POST | `/api/orders` | đăng nhập | `{items:[{productId, quantity}], shippingAddress, paymentMethod: MOCK_CARD\|MOCK_WALLET\|MOCK_FAIL, voucherCode?}` → **202** + đơn `PENDING` |
| GET | `/api/orders/my?page=` | đăng nhập | Đơn của tôi |
| GET | `/api/orders/{id}` | chủ đơn / nhân viên | Chi tiết + lịch sử trạng thái |
| POST | `/api/orders/{id}/cancel` | chủ đơn | Hủy (trước khi giao) |
| GET | `/api/orders?status=&page=` | ADMIN, WAREHOUSE_MANAGER, ANALYST | Tất cả đơn |
| PUT | `/api/orders/{id}/status` | ADMIN, WAREHOUSE_MANAGER | `{status: CANCELLED, note}` – chỉ còn hủy đơn; SHIPPING/DELIVERED do Shipping Service cập nhật qua `shipping-events` |

Đơn trả thêm `subtotal`, `shippingFee` (miễn phí từ 500.000 ₫, dưới mức đó 30.000 ₫); với nhân viên có thêm `riskScore`, `riskLevel`. Trạng thái mới `UNDER_REVIEW`: đơn bị Fraud Service gắn cờ, chờ xác minh.

Khuyến mãi (2.1): `voucherCode`, `itemDiscount` (flash sale), `voucherDiscount`, `discount`, `promotionSummary`, `promotionPending` (Promotion Service chưa định giá xong – `totalAmount` lúc này là tạm tính theo giá niêm yết). Mỗi dòng hàng có `salePrice` (giá flash sale, null nếu không có); `lineTotal` tính theo giá thực trả. Phí vận chuyển tính trên giá niêm yết.

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
| GET | `/api/notifications/admin/alerts?page=` | **ADMIN** – lịch sử cảnh báo hệ thống nhận từ Alertmanager + kết quả gửi từng kênh |
| GET | `/api/notifications/admin/alerts/channels` | **ADMIN** – kênh đang bật (`in-app`, `slack`, `discord`, `telegram`, `email`; không lộ URL/token) |
| POST | `/api/notifications/admin/alerts/test` | **ADMIN** – gửi cảnh báo thử qua mọi kênh đang bật |
| POST | `/internal/alerts` | Webhook cho Alertmanager (Bearer `ALERT_WEBHOOK_TOKEN`); **không** có route qua Gateway |

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
| PUT | `/api/simulator/settings` | `{sessionsPerMinute, maxConcurrentSessions, browseOnlyRate, cartAbandonRate, paymentFailureRate, cancelRate, newCustomerRate, wave, autoRestock, autoFulfill, suspiciousRate, reviewRate, autoRiskReview}` |
| POST | `/api/simulator/burst` | `{orders: 60, seconds: 30}` – mua dồn dập |
| POST | `/api/simulator/reset-stats` | Đặt lại thống kê |

## Fraud – `fraud-service` (ADMIN, ANALYST)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/fraud/cases?status=PENDING_REVIEW|APPROVED|REJECTED|CLOSED|AUTO_APPROVED&level=&page=` | Hồ sơ rủi ro (điểm, mức, danh sách tín hiệu kèm giải thích) |
| GET | `/api/fraud/cases/{orderId}` | Chi tiết hồ sơ |
| POST | `/api/fraud/cases/{orderId}/review` | `{approved: true|false, note}` – note bắt buộc khi từ chối → phát `RiskReviewed` |
| GET | `/api/fraud/summary` | Số hồ sơ theo trạng thái / mức + mô tả bộ luật |

## Shipping – `shipping-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/shipments/orders/{orderId}` | chủ đơn / nhân viên | Vận đơn + timeline (ĐVVC, mã vận đơn, các mốc) |
| GET | `/api/shipments/carriers` | công khai | Đơn vị vận chuyển + phí |
| GET | `/api/shipments?status=CREATED|PACKED|HANDED_OVER|IN_TRANSIT|DELIVERED|CANCELLED&page=` | ADMIN, WAREHOUSE_MANAGER, ANALYST | Hàng đợi vận đơn (chờ lâu nhất lên đầu khi lọc theo trạng thái) |
| GET | `/api/shipments/summary`, `/api/shipments/{id}` | nhân viên | Thống kê / chi tiết |
| PUT | `/api/shipments/{id}/status` | ADMIN, WAREHOUSE_MANAGER | `{status, note}` – chỉ bước kế tiếp hợp lệ (`nextStatuses`) |
| PUT | `/api/shipments/{id}/carrier` | ADMIN, WAREHOUSE_MANAGER | `{carrier: GHN|GHTK|VTP}` – trước khi bàn giao |

## Loyalty – `loyalty-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/loyalty/me` | đăng nhập | Điểm, hạng, chi tiêu 12 tháng, tiến độ lên hạng, mã giới thiệu |
| GET | `/api/loyalty/me/transactions?page=` | đăng nhập | Sổ cái điểm |
| GET | `/api/loyalty/tiers` | công khai | Bảng hạng thành viên |
| GET | `/api/loyalty/referrals/{code}` | công khai | Kiểm tra mã giới thiệu (form đăng ký) |
| GET | `/api/loyalty/admin/accounts?page=` | ADMIN, ANALYST | Tài khoản nhiều điểm nhất |
| GET | `/api/loyalty/rewards` | công khai | Danh mục phần thưởng đổi điểm `{id, name, points, voucherType, value, maxDiscount, minOrderAmount, validDays}` |
| POST | `/api/loyalty/rewards/{id}/redeem` | khách hàng | Trừ điểm → `{redemptionId, rewardName, pointsSpent, balance, message}`; voucher cá nhân do Promotion Service phát sau vài giây (409 nếu không đủ điểm) |

`POST /api/auth/register` nhận thêm `referralCode` (tùy chọn).

## Promotion – `promotion-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/promotions/flash-sales/active` | công khai | Flash sale đang diễn ra + sắp diễn ra (7 ngày) với `salePrice`, `quota`, `sold`, `remaining`, `perUserLimit`, `status` |
| GET | `/api/promotions/vouchers/public` | công khai | Mã giảm giá công khai còn hiệu lực |
| POST | `/api/promotions/quote` | đăng nhập | `{items:[{productId, quantity}], voucherCode?, shippingFee?}` → giá flash sale từng dòng, `itemDiscount`, `voucherValid`, `voucherDiscount`, `voucherMessage`, `warnings[]` (không giữ suất) |
| GET | `/api/promotions/vouchers/mine` | đăng nhập | Voucher cá nhân (đổi từ điểm) + trạng thái `AVAILABLE\|USED\|EXPIRED\|DISABLED` |
| GET / POST | `/api/promotions/admin/flash-sales` | xem: ADMIN, PRODUCT_MANAGER, ANALYST · tạo: ADMIN, PRODUCT_MANAGER | `{name, description, startsAt, endsAt, items:[{productId, salePrice, quota, perUserLimit}]}` – giá sale < giá bán, không trùng khung giờ cho cùng sản phẩm, tối đa 30 ngày |
| POST | `/api/promotions/admin/flash-sales/{id}/cancel` | ADMIN, PRODUCT_MANAGER | Hủy chiến dịch (đơn đã đặt giữ nguyên giá) |
| GET / POST | `/api/promotions/admin/vouchers?q=&source=` | như trên | `{code, description, type: PERCENT\|FIXED\|FREE_SHIPPING, value, maxDiscount, minOrderAmount, startsAt, endsAt, usageLimit?, perUserLimit}` |
| PUT | `/api/promotions/admin/vouchers/{id}/status` | ADMIN, PRODUCT_MANAGER | `{active}` |
| GET | `/api/promotions/admin/stats?days=30` | ADMIN, PRODUCT_MANAGER, ANALYST | Số đơn có ưu đãi, tổng giảm flash sale / voucher, mã dùng nhiều nhất, tiến độ bán flash sale |

Dữ liệu mẫu (khi CSDL trống, `PROMOTION_SEED=true`): mã `WELCOME10` (10%, tối đa 50k, đơn từ 200k, 1 lần/khách), `FREESHIP` (3 lần/khách), `GIAM50K` (đơn từ 500k, 500 lượt) và "Flash Sale khai trương" 3 ngày cho 4 sản phẩm giá cao (giảm ~20%, 30 suất, tối đa 2/khách).

## Audit – `audit-service` (ADMIN)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/audit?q=&action=&resourceType=&resourceId=&outcome=&actor=&source=&from=&to=&page=` | Tra cứu nhật ký (mới nhất trước); `q` tìm trong chi tiết / email / IP / correlationId |
| GET | `/api/audit/summary?hours=24` | Tổng số, số thất bại, đăng nhập sai, tài khoản bị khóa, thao tác nhiều nhất, IP nhiều lần thất bại |
| GET | `/api/audit/actions` | Danh sách mã thao tác đã ghi nhận |

Bản ghi: `{occurredAt, source, correlationId, actorId, actorEmail, actorRoles, action, resourceType, resourceId, outcome: SUCCESS|FAILURE, detail, clientIp}`. Thao tác được ghi: đăng nhập (sai/bị chặn/khóa; thành công của nhân viên), đổi vai trò, khóa tài khoản, sản phẩm & danh mục, nhập kho & ngưỡng tồn, nhân viên hủy đơn, duyệt/từ chối đơn rủi ro, cập nhật vận đơn, ẩn/hiện đánh giá, flash sale & voucher.

## Support – `support-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/support/tickets` | khách hàng | `{subject, category: ORDER\|PAYMENT\|SHIPPING\|PRODUCT\|PROMOTION\|ACCOUNT\|OTHER, orderId?, message}` → **201** `{ticket, messages, order}`; `orderId` phải là đơn của chính khách; tối đa 5 yêu cầu đang mở (409) |
| GET | `/api/support/tickets/mine?page=` | đăng nhập | Yêu cầu của tôi (`unread` = tin chưa đọc) |
| GET | `/api/support/orders/mine` | đăng nhập | Đơn gần đây để chọn khi tạo yêu cầu |
| GET | `/api/support/tickets/{id}` | chủ yêu cầu / CSKH / ADMIN / ANALYST | Chi tiết + tin nhắn + ngữ cảnh đơn; đánh dấu đã đọc. Người khác → 404 |
| POST | `/api/support/tickets/{id}/messages` | chủ yêu cầu / ADMIN, SUPPORT_AGENT | `{content}` (≤ 2000 ký tự). Khách trả lời yêu cầu đã giải quyết → mở lại; yêu cầu đã đóng → 409 |
| POST | `/api/support/tickets/{id}/close` | chủ yêu cầu | Khách tự đóng |
| POST | `/api/support/tickets/{id}/rating` | chủ yêu cầu | `{rating 1–5, comment}` – sau khi đã giải quyết, một lần |
| GET | `/api/support/tickets/{id}/stream` | như xem chi tiết | **SSE**: event `message` (tin nhắn mới), `ticket` (trạng thái mới) |
| GET | `/api/support/admin/tickets?status=ACTIVE\|OPEN\|…&priority=&assignee=ME\|NONE&overdue=true&q=&page=` | ADMIN, SUPPORT_AGENT, ANALYST | Hàng đợi, sắp theo ưu tiên (Khẩn → Thấp) rồi tin nhắn mới nhất; `q` tìm `HT-<id>`, số đơn, email, tiêu đề |
| PUT | `/api/support/admin/tickets/{id}` | ADMIN, SUPPORT_AGENT | `{status?: IN_PROGRESS\|WAITING_CUSTOMER\|RESOLVED\|CLOSED, priority?, assignee?: ME\|NONE, note?}` – ghi tin nhắn hệ thống + audit |
| GET | `/api/support/admin/summary` | ADMIN, SUPPORT_AGENT, ANALYST | Số yêu cầu theo trạng thái, chưa nhận, quá hạn SLA, giải quyết hôm nay, thời gian phản hồi đầu tiên TB, điểm hài lòng TB |
| GET | `/api/support/admin/stream` | ADMIN, SUPPORT_AGENT, ANALYST | **SSE** hàng đợi: event `ticket` khi có yêu cầu mới / cập nhật |

SLA phản hồi đầu tiên: Khẩn 15 phút · Cao 30 phút (mặc định cho chủ đề thanh toán hoặc đơn ≥ 10 triệu) · Thường 2 giờ (`SUPPORT_SLA_NORMAL`) · Thấp 8 giờ. Yêu cầu đã giải quyết tự đóng sau 3 ngày không phản hồi. Tài khoản CSKH mẫu: `support@shop.local / Support@123` (vai trò mới `SUPPORT_AGENT`).

## Recommendation – `recommendation-service`

Mọi API gợi ý là **công khai**; gửi kèm token thì nhận gợi ý cá nhân hóa. Kết quả: `{placement, strategy: HYBRID|CO_PURCHASE|PERSONALIZED|TRENDING, items: [{productId, name, price, imageUrl, categoryId, categoryName, score, reason}]}` – `reason` là lý do hiển thị cho khách ("Vì bạn đã xem …", "Thường được mua cùng · 12 đơn"...). `limit` tối đa 24.

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/recommendations/for-you?limit=12` | công khai | "Dành cho bạn": hồ sơ sở thích (xem/giỏ/mua, danh mục, từ khóa đã tìm) → láng giềng item-item + thịnh hành theo danh mục, đa dạng hóa. Khách vãng lai / khách mới → `TRENDING` |
| GET | `/api/recommendations/products/{id}/similar?limit=8` | công khai | "Sản phẩm tương tự": khách cũng xem 45% + mua cùng 25% + nội dung (danh mục, giá, tên) 30%; sản phẩm mới chưa có hành vi vẫn có gợi ý |
| GET | `/api/recommendations/products/{id}/bought-together?limit=4` | công khai | "Thường được mua cùng" (đơn đã thanh toán); rỗng nếu chưa có dữ liệu |
| GET | `/api/recommendations/cart?productIds=1,2&limit=6` | công khai | "Có thể bạn cũng cần" cho giỏ hàng, bổ sung bằng sản phẩm thịnh hành |
| GET | `/api/recommendations/trending?limit=12&categoryId=` | công khai | Xem + 3×giỏ + 5×mua theo ngày, chu kỳ bán rã 2 ngày, 7 ngày gần nhất |
| POST | `/api/recommendations/clicks` | công khai | `{placement, productId}` → **202**. Đo CTR; khách đã đăng nhập mua sản phẩm đã bấm trong 24 giờ → tính chuyển đổi cho vị trí đó |
| GET | `/api/recommendations/admin/stats?days=7` | ADMIN, ANALYST, PRODUCT_MANAGER | Theo vị trí: lượt gọi, hiển thị, bấm, CTR, đơn, tỷ lệ chuyển đổi, doanh thu; theo ngày; tình trạng mô hình; top cặp mua cùng |
| GET | `/api/recommendations/admin/users/{userId}?limit=12` | ADMIN, ANALYST, PRODUCT_MANAGER | Giải thích: sản phẩm đã tương tác (trọng số), danh mục quan tâm, từ khóa đã tìm + danh sách "Dành cho bạn" của khách (không tính vào thống kê) |

## Giám sát

Không đi qua Gateway. Mỗi service mở `/actuator/prometheus` trên cổng nội bộ (Gateway: cổng quản trị 9080, không publish). Prometheus `http://localhost:9090` (chỉ localhost), Grafana `http://localhost:3001` (admin / `GRAFANA_PASSWORD`, dashboard "Shop Platform – Tổng quan hệ thống"), Alertmanager `http://localhost:9093`, hộp thư Mailpit (email cảnh báo) `http://localhost:8025` – ba địa chỉ sau chỉ mở trên localhost.

## Review – `review-service`

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/reviews/products/{productId}?rating=&page=` | công khai | Đánh giá đang hiển thị |
| GET | `/api/reviews/products/{productId}/summary` | công khai | Điểm trung bình, số lượng, phân bố 1–5 sao |
| GET | `/api/reviews/products/{productId}/eligibility` | công khai (đăng nhập để có kết quả) | `{canReview, alreadyReviewed, reason}` |
| POST | `/api/reviews/products/{productId}` | người đã nhận hàng | `{rating 1-5, comment, images[≤5 URL]}` – mỗi người một lần / sản phẩm |
| GET / POST | `/api/reviews/products/{productId}/questions` | GET công khai, POST đăng nhập | Hỏi đáp `{content}` |
| POST | `/api/reviews/questions/{id}/answers` | ADMIN, PRODUCT_MANAGER hoặc người đã mua | `{content}` – báo cho người hỏi |
| GET | `/api/reviews/admin/reviews?visible=` | ADMIN, PRODUCT_MANAGER | Kiểm duyệt |
| PUT | `/api/reviews/admin/reviews/{id}/visibility` | ADMIN, PRODUCT_MANAGER | `{visible, note}` |
| GET | `/api/reviews/admin/questions?answered=false` | ADMIN, PRODUCT_MANAGER | Câu hỏi chờ trả lời |

## Truy vết (correlationId)

Mọi response qua Gateway có header `X-Correlation-Id` (giữ giá trị client gửi nếu hợp lệ). Giá trị này đi theo mọi sự kiện Kafka phát ra trong luồng xử lý (header `correlationId` + trường `correlationId` trong envelope) và xuất hiện trong log của mọi service dạng `[<correlationId>]`.

## Ví dụ với curl

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer@shop.local","password":"Customer@123"}' | jq -r .accessToken)

curl -s "localhost:8080/api/products?q=tai%20nghe" | jq '.content[] | {id, name, price}'

curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":8,"quantity":1}],"shippingAddress":"1 Lê Lợi, Q1, TP.HCM","paymentMethod":"MOCK_CARD"}'

curl -N "localhost:8080/api/notifications/stream?access_token=$TOKEN"   # theo dõi realtime
```
