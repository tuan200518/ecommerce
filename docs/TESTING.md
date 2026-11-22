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
| ≥ 6 topic trong luồng nghiệp vụ | 8 topic + 8 topic DLT |
| Producer & Consumer thực tế | Outbox relay (producer) + listener ở 8 service + Kafka Streams |
| Analytics dùng Kafka Streams | `AnalyticsTopology` (windowing, KTable, aggregation) |
| Dashboard gần thời gian thực | SSE, cập nhật ≤ 1 giây |
| Xử lý lỗi, retry, chống trùng | DefaultErrorHandler + backoff + DLT, `processed_events`, Outbox |
| Dữ liệu giả lập & kiểm thử tải | Seed dữ liệu tự động, k6 `simulate-traffic.js`, `flash-sale.js` |
| README, kiến trúc, API, hướng dẫn | `README.md`, `docs/` |
