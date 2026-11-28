# Kiểm thử, kịch bản demo và tiêu chí nghiệm thu

## 1. Test tự động

```bash
mvn test
```

Không cần Docker: Kafka dùng **Embedded Kafka (KRaft)**, database dùng **H2 ở chế độ PostgreSQL**, Kafka Streams dùng **TopologyTestDriver**. Riêng `FlashSalePostgresTest` chạy trên PostgreSQL thật qua **Testcontainers** và tự bỏ qua nếu máy không có Docker.

## 2. Đối chiếu test case (mục 7 của planning)

| Mã | Test case | Cách kiểm chứng |
|---|---|---|
| TC01 | Tạo đơn mới → OrderCreated được xử lý | `OrderSagaIntegrationTest.happyPath…`: đơn PENDING, OrderCreated xuất hiện trên `order-events` qua Outbox. Thủ công: Kafka UI → topic `order-events`. |
| TC02 | Thanh toán thành công | Cùng test: InventoryReserved → AWAITING_PAYMENT → PaymentCompleted → CONFIRMED + OrderConfirmed. |
| TC03 | Thanh toán thất bại | `paymentFailure_cancelsOrder…` + `PaymentServiceTest.failingMethod…`. Thủ công: đặt hàng với "Thẻ bị từ chối" → đơn CANCELLED, kho hoàn lại (xem lịch sử kho). |
| TC04 | Sản phẩm hết hàng | `outOfStock_cancelsOrder` + `FlashSaleH2Test.flashSale_neverOversells`. |
| TC05 | Tạo nhiều đơn liên tục → Analytics chính xác | `AnalyticsTopologyTest` (doanh thu cửa sổ, trạng thái, top sản phẩm). Thủ công: chạy `simulate-traffic.js`, so sánh Dashboard với trang Thanh toán (summary). |
| TC06 | Kafka tạm ngừng rồi khôi phục | Kịch bản C bên dưới: `docker compose stop kafka` → đặt hàng (API vẫn nhận, sự kiện nằm trong outbox) → `docker compose start kafka` → OutboxRelay publish lại, consumer xử lý tiếp. |
| TC07 | Consumer gặp lỗi → Retry/DLQ | `malformedMessage_doesNotBlockConsumer` (message hỏng → `payment-events-dlt`, đơn sau vẫn được xử lý). Log hiển thị "Retry lần n" và "Đưa message vào DLT". |
| TC08 | Dashboard nhận sự kiện mới | Mở Dashboard, đặt một đơn → KPI và biểu đồ cập nhật trong ~1–2 giây (SSE). |
| TC09 | Gửi lại cùng sự kiện | `happyPath…` gửi lại PaymentCompleted cùng eventId → chỉ 1 lần CONFIRMED, 1 OrderConfirmed. `PaymentServiceTest`, `FlashSaleScenario.cancelReleasesStock…` cũng kiểm tra trùng. |
| TC10 | Nhiều consumer cùng hoạt động | Mỗi listener `concurrency=3` = 3 partition. Kafka UI → Consumers: mỗi group có 3 member, mỗi member 1 partition. Scale ngang: `docker compose up -d --scale inventory-service=2` → partition được chia lại giữa các instance. |

## 2b. Test tự động cho Ecommerce 2.0

| Bộ test | Nội dung |
|---|---|
| `EventEnvelopeTest`, `OutboxCorrelationTest` | correlationId kế thừa theo luồng, message cũ không có trường mới vẫn đọc được, `eventVersion` alias, bảng outbox cũ tự thêm cột `correlation_id` |
| `OrderSagaIntegrationTest` (9 test) | Thêm: chờ đủ **giữ hàng + rủi ro** theo thứ tự bất kỳ, rủi ro cao → `UNDER_REVIEW` (không yêu cầu thanh toán) → duyệt → `AWAITING_PAYMENT`, từ chối → `CANCELLED` + OrderCancelled, shipping-events → `SHIPPING` → `DELIVERED`, nhân viên không đặt tay SHIPPING, phí vận chuyển, khách không thấy điểm rủi ro |
| `RiskRulesTest`, `FraudServiceTest` | Một tín hiệu mạnh không đủ để chặn đơn, tài khoản mới + đơn lớn → HIGH, cộng dồn + giới hạn 100 điểm, phát hiện địa chỉ dùng chung (chuẩn hóa bỏ dấu) và tần suất đặt, idempotent, phân quyền + bắt buộc lý do khi từ chối, hủy đơn → đóng hồ sơ, bỏ qua OrderCreated cũ khi replay |
| `ShippingServiceTest` | Tạo vận đơn đúng 1 lần, chọn ĐVVC, không nhảy bước, khóa đổi ĐVVC sau bàn giao, timeline, khách không xem được vận đơn người khác, hủy đơn → hủy vận đơn, đồng bộ dữ liệu cũ không phát sự kiện |
| `LoyaltyServiceTest` | Tích điểm + thưởng đơn đầu đúng 1 lần, lên hạng → hệ số mới, hủy đơn trừ điểm và mở lại nhiệm vụ, giới thiệu (mã sai/tự giới thiệu bị bỏ qua, thu hồi khi đơn bị hủy), nhân viên không tham gia, giới hạn thưởng đánh giá/ngày |
| `ReviewServiceTest` | Chỉ người đã **nhận** hàng được đánh giá, một lần/sản phẩm, ẩn email, tổng quan sao + kiểm duyệt, hỏi đáp và thông báo |
| `JwtAuthenticationFilterTest` | Thêm: phân quyền route của 4 service mới, sinh/giữ `X-Correlation-Id` |
| `AnalyticsTopologyTest` | Thêm: OrderFlagged → cảnh báo, ProductViewed → bộ đếm, chỉ khách đăng nhập tính là người dùng hoạt động |

## 2c. Test tự động cho Ecommerce 2.1

| Bộ test | Nội dung |
|---|---|
| `PricingEngineTest` | Flash sale áp giá từng dòng, voucher tính trên tiền hàng sau flash sale, trần % giảm, FIXED không vượt tiền hàng, FREE_SHIPPING = phí ship (0 nếu đơn đã miễn phí), mức đơn tối thiểu, hết suất / vượt giới hạn mỗi khách, làm tròn xuống đồng |
| `PromotionServiceTest` (8 test) | Mọi đơn đều có quyết định định giá; giữ suất nguyên tử + giới hạn mỗi khách + hết suất; hủy đơn hoàn suất/lượt (gửi lại sự kiện hủy không hoàn thêm); voucher: lượt mỗi khách, tổng lượt, khôi phục sau hủy, mã không tồn tại; mã cá nhân từ Loyalty chỉ chủ sở hữu dùng, phát đúng 1 lần; báo giá không giữ suất; bỏ qua OrderCreated cũ; validate quản trị (giá sale, trùng khung giờ, quyền) + audit |
| `OrderSagaIntegrationTest` (11 test) | Thêm: chờ **ba** điều kiện (kho + rủi ro + khuyến mãi); PromotionApplied giảm tổng tiền, giá flash sale từng dòng, PaymentRequested mang số tiền sau giảm; PromotionRejected → hủy kèm lý do + OrderCancelled |
| `LoyaltyServiceTest` | Thêm: đổi điểm trừ số dư (không giảm tổng tích lũy), phát PointsRedeemed kèm thông số voucher, không đủ điểm / phần thưởng không tồn tại / nhân viên bị từ chối, hủy đơn sau khi đổi vẫn trừ điểm tích |
| `LoginSecurityTest` | Sai mật khẩu N lần → 423 kể cả đúng mật khẩu, đăng nhập thành công đặt lại bộ đếm, email không tồn tại trả cùng lỗi, audit cho đăng nhập sai / khóa / đổi vai trò / khóa tài khoản |
| `AuditServiceTest` | Lưu idempotent theo eventId, lọc theo đối tượng / nội dung / người thao tác, thoát ký tự LIKE, chỉ ADMIN đọc được, cảnh báo khi khóa tài khoản và khi một IP thử ≥ N tài khoản (đúng 1 lần) |
| `RateLimitFilterTest` | Đăng nhập giới hạn theo IP (429 + Retry-After + metric), đặt hàng giới hạn theo người dùng (không ảnh hưởng người khác cùng IP), Redis lỗi → cho qua, tắt rate limit, chỉ tin `X-Real-IP` từ proxy nội bộ |
| `JwtAuthenticationFilterTest` | Thêm: phân quyền Promotion/Audit, `X-Client-Ip` client gửi bị ghi đè |

## 2d. Test tự động cho Ecommerce 2.2

| Bộ test | Nội dung |
|---|---|
| `AlertRelayTest` | Webhook Alertmanager bắt buộc token; cảnh báo đi tới thông báo trong ứng dụng cho ADMIN + Slack / Discord / Telegram (HTTP server giả trong test) + email; định dạng tiếng Việt, giờ Việt Nam, link Grafana; một kênh lỗi không chặn kênh khác và được ghi trong lịch sử; cảnh báo "đã khôi phục"; API kênh / gửi thử chỉ cho ADMIN |
| `SupportServiceTest` (6 test) | Chỉ gắn được đơn của chính mình, không xem được yêu cầu người khác (404); luồng hội thoại: nhận xử lý tự động, tin chưa đọc, chờ khách → đang xử lý, giải quyết → khách trả lời mở lại, đóng, đánh giá một lần; ANALYST chỉ xem; ưu tiên cao + quá hạn SLA cảnh báo đúng một lần, hàng đợi sắp theo mức ưu tiên thật, tìm theo `HT-<id>`; sự kiện đơn hàng mới → tin nhắn hệ thống (bỏ qua sự kiện cũ); giới hạn yêu cầu đang mở, tự đóng |
| `AnalyticsTopologyTest` | Thêm: KPI giảm giá ròng (đơn hủy bị trừ, đơn giảm 0đ không tính), bộ đếm yêu cầu hỗ trợ tạo mới / đã giải quyết |
| `PromotionServiceTest` | Thêm: hủy đơn phát đúng một `PromotionReleased` |
| `UpstreamUnavailableFilterTest` | Service phía sau chưa chạy → 503 + Retry-After thay vì 500 |
| `JwtAuthenticationFilterTest` | Thêm: phân quyền hàng đợi CSKH, lịch sử cảnh báo chỉ ADMIN, webhook nội bộ không công khai |

## 2e. Test tự động cho Ecommerce 2.3

| Bộ test | Nội dung |
|---|---|
| `ScoringTest` | Cosine có hệ số tin cậy (cặp hiếm không lấn át cặp nhiều bằng chứng), độ tương đồng nội dung (danh mục > giá > tên), tách từ tiếng Việt bỏ dấu + khớp từ khóa tìm kiếm, suy giảm half-life, hệ số chất lượng Bayes, đa dạng hóa theo danh mục |
| `RecommendationServiceTest` (7 test) | Mua cùng xếp theo số đơn, đối xứng, bỏ sản phẩm ngừng bán; sự kiện đọc lại chỉ học một lần; "tương tự" kết hợp cùng xem + nội dung, xem lại trong phiên không tạo cặp, sản phẩm mới vẫn có gợi ý (cold start); "Dành cho bạn" cá nhân hóa kèm lý do (đã thêm vào giỏ, đã tìm "…"), không gợi ý lại sản phẩm đã tương tác, khách mới → thịnh hành, không bao giờ rỗng kể cả khi khách đã tương tác với cả danh mục, giải thích hồ sơ chỉ cho nhân viên; thịnh hành theo trọng số xem/giỏ/mua và theo danh mục; giỏ hàng; CTR + chuyển đổi + doanh thu theo vị trí, một lượt bấm chỉ tính một đơn |
| `CatalogRepublisherTest` | Product Service phát lại mọi sản phẩm lên product-events với trạng thái hiện tại (đang bán → ProductUpdated, ngừng bán → ProductDiscontinued) |
| `DashboardSnapshotTest` | Snapshot khôi phục đủ KPI, chuỗi doanh thu, top sản phẩm, cảnh báo (không tính lại cảnh báo cũ); đọc tiếp ngay sau offset cuối; topic tạo lại → bỏ snapshot; không ghi lại khi không đổi; snapshot hỏng → dựng lại từ đầu |
| `JwtAuthenticationFilterTest` | Thêm: API gợi ý công khai (khách đã đăng nhập vẫn được chuyển danh tính), thống kê / hồ sơ gợi ý chỉ cho nhân viên |

## 3. Kịch bản demo nâng cao (mục 9.1)

### A. Real-time Sales Analytics

Cách nhanh nhất: khách hàng ảo (`simulator-service`) chạy sẵn → mở Dashboard là thấy số liệu chạy. Tăng tốc độ ở `/admin/simulator`, bấm **Mua dồn dập** (vd. 60 đơn / 30 giây) để thấy cảnh báo "Đơn hàng tăng đột biến".

Hoặc tạo tải lớn bằng k6:

```bash
docker compose --profile loadtest run --rm k6 run -e VUS=30 -e DURATION=3m /scripts/simulate-traffic.js
```

Mở `http://localhost:3000/admin` (analyst/admin): biểu đồ doanh thu chuyển giữa cửa sổ **1 phút / 5 phút / 1 giờ** (Kafka Streams windowing), đơn/phút, trạng thái đơn, top sản phẩm, phễu chuyển đổi đều cập nhật liên tục. Khi số đơn/phút vượt `ORDER_SPIKE_THRESHOLD` (mặc định 30) → cảnh báo **"Đơn hàng tăng đột biến"**. Trang **Báo cáo** đọc từ bảng tổng hợp `sales_aggregates`.

Với ~1.000 lượt đặt hàng, hệ thống xử lý **> 10.000 sự kiện** (xem cột Messages trong bảng "Hiệu suất Kafka" trên Dashboard hoặc Kafka UI).

### B. Flash Sale Simulation

Sản phẩm `[FLASH SALE] Tai nghe Buds Mini` (SKU `FLS-001`) chỉ có **20** cái.

```bash
docker compose --profile loadtest run --rm k6 run -e BUYERS=200 /scripts/flash-sale.js
```

Kết quả mong đợi (in ở cuối k6): `khả dụng=0`, đã bán + đang giữ ≤ 20, check "không bán vượt tồn kho" đạt. Trên Dashboard xuất hiện cảnh báo **Tồn kho thấp**; ~180 đơn bị hủy với lý do "Không đủ tồn kho". Cơ chế: `UPDATE ... WHERE available >= qty` (nguyên tử ở mức database), đã được chứng minh bằng `FlashSaleH2Test` / `FlashSalePostgresTest`.

### C. Fault Tolerance

1. **Consumer dừng giữa chừng**
   ```bash
   docker compose stop inventory-service
   # Đặt vài đơn trên web → đơn đứng ở PENDING; Dashboard: lag của group inventory-service.order-events tăng
   docker compose start inventory-service
   # Service tiếp tục từ offset đã commit → lag về 0, đơn chuyển CONFIRMED
   ```
   Nếu dừng lâu hơn `ORDER_TIMEOUT` (2 phút), đơn bị hủy do timeout và khi Inventory chạy lại sẽ nhận OrderCreated rồi OrderCancelled theo đúng thứ tự (cùng partition) → giữ hàng rồi hoàn kho, tồn kho cuối cùng vẫn đúng.

2. **Kafka ngừng hoạt động**
   ```bash
   docker compose stop kafka
   # Vẫn đặt được hàng (202): sự kiện nằm an toàn trong bảng outbox_events
   docker compose start kafka
   # OutboxRelay tự publish lại theo thứ tự, Saga tiếp tục
   ```

3. **Retry / DLQ**
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh \
     --bootstrap-server localhost:9092 --topic order-events --property parse.key=true --property key.separator=:
   > 999:{"not":"an event"}
   ```
   Các consumer của `order-events` đẩy message vào `order-events-dlt` (xem Kafka UI), partition không bị chặn.

4. **Idempotency**: trong Kafka UI, mở một message `PaymentCompleted` trên `payment-events`, sao chép key + value rồi dùng *Produce Message* gửi lại y nguyên (cùng `eventId`) → đơn hàng không thay đổi, không có OrderConfirmed thứ hai (bảng `processed_events` chặn lại).

## 4. Kiểm thử tải (k6)

`simulate-traffic.js` đặt ngưỡng: `http_req_failed < 5%`, `p95(http_req_duration) < 1.5s`, `p95(order_create_latency) < 1s`. Tạo đơn chỉ ghi DB + outbox nên độ trễ thấp; phần nặng (giữ hàng, thanh toán, tổng hợp) diễn ra bất đồng bộ — theo dõi consumer lag trên Dashboard để đánh giá năng lực xử lý.

## 5. Tiêu chí nghiệm thu (mục 10)

| Tiêu chí | Trạng thái |
|---|---|
| Khởi chạy bằng Docker Compose | `docker compose up -d --build` |
| Đăng ký, đăng nhập, phân quyền | Auth Service + JWT + Gateway policy + kiểm tra ở service; 5 vai trò |
| Quản lý sản phẩm, giỏ hàng, đơn hàng | Product / Cart / Order Service + trang quản trị |
| Đặt hàng + thanh toán mô phỏng | Saga Order ↔ Inventory ↔ Payment |
| Giao tiếp bất đồng bộ qua Kafka | Không có lời gọi REST giữa các service |
| ≥ 6 topic trong luồng nghiệp vụ | 15 topic + 15 topic DLT |
| Producer & Consumer thực tế | Outbox relay (producer) + listener ở 14 service + Kafka Streams |
| Analytics dùng Kafka Streams | `AnalyticsTopology` (windowing, KTable, aggregation) |
| Dashboard gần thời gian thực | SSE, cập nhật ≤ 1 giây |
| Xử lý lỗi, retry, chống trùng | DefaultErrorHandler + backoff + DLT, `processed_events`, Outbox |
| Dữ liệu giả lập & kiểm thử tải | Seed dữ liệu tự động, k6 `simulate-traffic.js`, `flash-sale.js` |
| README, kiến trúc, API, hướng dẫn | `README.md`, `docs/` |

## 6. Kịch bản demo Ecommerce 2.0

### D. Phát hiện gian lận + xác minh thủ công

1. Đăng ký tài khoản mới, đặt đơn ≥ 100 triệu (vd. 4 × Laptop Gaming Raptor 15, hoặc 12 × điện thoại Fold Z).
2. Trang đơn hàng hiển thị **"Đang xác minh"** (`UNDER_REVIEW`); hàng vẫn được giữ, chưa bị trừ tiền.
3. Đăng nhập `analyst@shop.local` → **Rủi ro & gian lận**: hồ sơ điểm ≥ 70 với các tín hiệu `VERY_HIGH_VALUE`, `NEW_ACCOUNT`... kèm giải thích; Dashboard có cảnh báo "Đơn #… nghi ngờ gian lận".
4. **Chấp nhận** → đơn sang `AWAITING_PAYMENT` → `CONFIRMED`; hoặc **Từ chối** (bắt buộc ghi chú) → `CANCELLED`, kho được hoàn lại.

Khách ảo có tỷ lệ "khách đáng ngờ" (mặc định 1%) và nhân viên xác minh ảo xử lý sau ~45 giây (tắt ở `/admin/simulator` để tự thao tác).

### E. Vận chuyển

Đơn `CONFIRMED` → Shipping Service tạo vận đơn. `warehouse@shop.local` → **Vận chuyển**: Đóng gói → Bàn giao (đơn chuyển **Đang giao**) → Đang giao → Đã giao (đơn **Đã giao**). Khách thấy ĐVVC, mã vận đơn và timeline ở trang chi tiết đơn. Kafka UI: topic `shipping-events`.

### F. Thành viên, giới thiệu, đánh giá

1. `customer@shop.local` → **Thành viên**: điểm, hạng, mã giới thiệu → sao chép link mời.
2. Mở link ở cửa sổ ẩn danh → đăng ký (mã được điền sẵn và kiểm tra) → đặt đơn đầu tiên → người giới thiệu nhận +500 điểm, người mới +200 (nhiệm vụ) + điểm đơn hàng.
3. Sau khi đơn **Đã giao**: trang sản phẩm cho phép viết đánh giá (+50 điểm); người chưa nhận hàng thấy lý do không được đánh giá. `pm@shop.local` → **Đánh giá & hỏi đáp**: trả lời câu hỏi, ẩn đánh giá (điểm thưởng của đánh giá đó bị trừ lại).
4. Hủy một đơn đã xác nhận → điểm của đơn bị trừ lại (lịch sử điểm hiển thị `REVERSAL`).

### G. Truy vết một luồng

Đặt hàng, xem header `X-Correlation-Id` của response (DevTools → Network). Lọc log theo giá trị đó:

```bash
docker compose logs order-service inventory-service payment-service fraud-service shipping-service | grep <correlationId>
```

Trên Kafka UI, mọi message của luồng (order-events, inventory-events, fraud-alert-events, payment-events, shipping-events...) có header `correlationId` giống nhau.

> Kiểm thử tải: luồng đặt hàng giờ có thêm bước chấm điểm rủi ro. Với k6 nhiều đơn liên tục trên ít tài khoản, luật VELOCITY có thể làm một số đơn cần xác minh; đặt `FRAUD_CHECK_ENABLED=false` nếu chỉ muốn đo thông lượng Saga gốc.

## 7. Kịch bản demo Ecommerce 2.1

### H. Flash sale + giới hạn mỗi khách

1. Đăng nhập `customer@shop.local`, mở **🔥 Khuyến mãi** (`/deals`): "Flash Sale khai trương" (tạo tự động sau ~30 giây khởi động) với đồng hồ đếm ngược và thanh "Đã bán".
2. Thêm 3 chiếc cùng một sản phẩm flash sale (tối đa 2/khách) vào giỏ → **Thanh toán**: báo giá hiện giá sale và cảnh báo "Mỗi khách chỉ được mua tối đa 2…", nút Đặt hàng bị khóa. Giảm còn 2 → đặt được, trang đơn hàng hiện "Đang áp dụng ưu đãi…" rồi giá flash sale từng dòng và tổng tiền sau giảm.
3. Quản trị (`admin@shop.local` hoặc `pm@shop.local`) → **🎁 Khuyến mãi** → tab Tổng quan: tiến độ bán, tổng giảm giá; tab Flash sale: tạo chiến dịch mới (giá sale ≥ giá bán hoặc trùng khung giờ sẽ bị từ chối).
4. Hủy đơn → suất flash sale được trả lại (số "Đã bán" giảm).

### I. Voucher + đổi điểm

1. Ở thanh toán nhập `WELCOME10` cho giỏ < 200.000 ₫ → "áp dụng cho đơn từ 200.000 ₫ (còn thiếu …)". Thêm hàng → mã hợp lệ, hiện dòng giảm giá. Đặt hàng → `PaymentRequested` trên Kafka UI mang số tiền sau giảm.
2. Đặt đơn thứ hai cũng với `WELCOME10` → báo giá báo "Bạn đã dùng hết số lượt cho mã WELCOME10".
3. Trang **Thành viên** → "Đổi điểm lấy voucher" (cần ≥ 200 điểm, khách mẫu tích điểm qua các đơn đã xác nhận) → sau vài giây voucher `PT…` xuất hiện ở "Voucher của tôi" và có thông báo; dùng mã ở thanh toán. Tài khoản khác nhập mã này → "không tồn tại".

### J. Chống dò mật khẩu + nhật ký kiểm toán

1. Đăng nhập sai mật khẩu `customer@shop.local` 5 lần → lần thứ 5 nhận "Tài khoản tạm khóa…" (HTTP 423); nhập đúng mật khẩu vẫn bị chặn trong 5 phút.
2. `admin@shop.local` nhận thông báo "Tài khoản bị tạm khóa". Mở **🔐 Nhật ký kiểm toán**: các dòng LOGIN_FAILED / ACCOUNT_LOCKED / LOGIN_BLOCKED kèm IP; mục "IP có nhiều lần thất bại".
3. Sửa giá một sản phẩm, nhập kho, hủy một đơn → mỗi thao tác có một dòng audit (giá cũ → mới, người thao tác, correlationId). Bấm lọc theo correlationId rồi dùng giá trị đó với `docker compose logs … | grep` (kịch bản G).
4. Rate limit: `for i in $(seq 1 25); do curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'; done` → sau 20 request/phút trả `429` (header `Retry-After`).

### K. Giám sát

1. Grafana `http://localhost:3001` (admin / `GRAFANA_PASSWORD`, mặc định `admin`) → dashboard "Shop Platform – Tổng quan hệ thống": 17 service up, request/giây, p95, lỗi 5xx, outbox, consumer lag, heap, kết nối DB, đăng nhập theo kết quả, định giá khuyến mãi.
2. `docker compose stop payment-service` → sau ~1 phút Prometheus (`http://localhost:9090/alerts`) bật **ServiceDown**, ô "Cảnh báo đang bật" trên Grafana chuyển đỏ; `docker compose start payment-service` để khôi phục.
3. `docker compose stop kafka` vài phút → `shop_outbox_pending` tăng trên dashboard (OutboxBacklog), giảm về 0 khi Kafka chạy lại.

> Kiểm thử tải với k6: rate limit mặc định cho phép 20 đơn/phút mỗi tài khoản và 300 lượt đăng nhập/phút mỗi IP. Khi chạy kịch bản cường độ cao (nhiều đơn/tài khoản), đặt `RATE_LIMIT_ENABLED=false` (và `PROMOTION_CHECK_ENABLED=false` nếu chỉ đo Saga gốc) rồi `docker compose up -d api-gateway order-service`.

## 8. Kịch bản demo Ecommerce 2.2

### L. Hỗ trợ khách hàng – chat realtime

1. Cửa sổ 1: `customer@shop.local` → mở một đơn hàng → **💬 Cần hỗ trợ về đơn này?** → chọn chủ đề "Thanh toán / hoàn tiền", gửi. Yêu cầu `HT-…` ưu tiên **Cao** (SLA 30 phút).
2. Cửa sổ 2 (ẩn danh): `support@shop.local / Support@123` → **🎧 Hỗ trợ khách hàng**: yêu cầu xuất hiện ngay trong tab "Chưa nhận" (SSE), chuông có thông báo "Yêu cầu hỗ trợ mới". Mở yêu cầu → trả lời → tự nhận xử lý, trạng thái "Đang xử lý".
3. Tin nhắn hiện ngay ở cửa sổ khách (không cần tải lại) kèm thông báo "CSKH đã trả lời". Khách trả lời → nhân viên thấy ngay.
4. Nhân viên đổi trạng thái **Đã giải quyết** → khách thấy ô đánh giá sao; khách gửi thêm tin → yêu cầu tự mở lại. Hủy đơn liên quan → tin nhắn hệ thống "Đơn hàng #… chuyển sang Đã hủy".
5. Dashboard: KPI "Yêu cầu hỗ trợ" tăng; Nhật ký kiểm toán có dòng `SUPPORT_TICKET_UPDATED`.

### M. Cảnh báo qua email/chat

1. ADMIN → **🚨 Cảnh báo hệ thống** → **Gửi cảnh báo thử**: kết quả `in-app: OK; email: OK`; mở Mailpit `http://localhost:8025` thấy email "🔵 [INFO] Cảnh báo thử nghiệm".
2. `docker compose stop payment-service` → sau ~3,5–4 phút (Prometheus `for: 3m` để không báo động giả lúc khởi động + Alertmanager gom 30 giây): chuông ADMIN và Mailpit nhận "🔴 [CRITICAL] Service payment-service không phản hồi" (nhiều service cùng ngừng → một tin liệt kê tất cả); Alertmanager `http://localhost:9093` hiện cảnh báo. `docker compose start payment-service` → "✅ Đã khôi phục…".
3. Slack / Discord / Telegram: đặt biến trong `.env` (vd. `ALERT_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/...`) → `docker compose up -d notification-service` → gửi cảnh báo thử. Email thật: đổi `SMTP_*` (Gmail cần App Password).
4. Cảnh báo nghiệp vụ: Simulator → **Mua dồn dập** 60 đơn / 30 giây → Dashboard hiện "Đơn hàng tăng đột biến" và vài phút sau email/chat nhận "Cảnh báo nghiệp vụ ORDER_SPIKE".

## 9. Kịch bản demo Ecommerce 2.3

### N. Gợi ý sản phẩm (AI Recommendation)

1. Để Simulator chạy vài phút (mô hình học từ toàn bộ lịch sử có sẵn ngay khi recommendation-service khởi động lần đầu).
2. Khách vãng lai mở trang chủ: khối **🔥 Đang thịnh hành**. Đăng nhập `customer@shop.local`, xem vài tai nghe, tìm "bàn phím", thêm một món vào giỏ → về trang chủ: khối **✨ Dành cho bạn** với lý do từng món ("Vì bạn đã thêm … vào giỏ", "Vì bạn đã tìm “bàn phím”", "Vì bạn quan tâm …").
3. Trang sản phẩm: **🛍️ Thường được mua cùng** (số đơn) và **Sản phẩm tương tự** ("Khách xem sản phẩm này cũng xem", "Cùng danh mục …, giá tương đương"). Giỏ hàng: **Có thể bạn cũng cần**.
4. Bấm một sản phẩm được gợi ý rồi đặt hàng và thanh toán → ADMIN/ANALYST mở **🧠 Gợi ý sản phẩm**: lượt bấm, CTR, "Sản phẩm bán nhờ gợi ý" và doanh thu theo từng vị trí (Simulator cũng bấm gợi ý với tỷ lệ "Bấm vào gợi ý"). Nhập userId của khách ở ô "Vì sao khách nhận được gợi ý này?" để xem hồ sơ sở thích.

### O. Dashboard khởi động nhanh

1. Mở Dashboard `/admin`, ghi nhớ các KPI. `docker compose restart analytics-service`.
2. Khi service lên lại (~1 phút), Dashboard có ngay số liệu cũ và tiếp tục cập nhật; log có dòng "Đã khôi phục Dashboard từ snapshot … đọc tiếp analytics-events từ offset {…}" thay vì đọc lại cả topic.

