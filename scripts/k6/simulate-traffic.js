/**
 * Mô phỏng lưu lượng mua sắm để tạo dữ liệu cho Dashboard và kiểm thử tải.
 *
 * Mỗi lượt (iteration): xem danh sách + chi tiết sản phẩm → thêm 1-3 sản phẩm vào giỏ → đặt hàng
 * (≈10% chọn MOCK_FAIL để kích hoạt luồng bù). Mỗi đơn sinh ra ~12 sự kiện Kafka
 * (cart, order, inventory, payment, notification, analytics) → 1.000 lượt ≈ 12.000+ sự kiện.
 *
 * Chạy:  k6 run scripts/k6/simulate-traffic.js
 *        docker compose --profile loadtest run --rm k6 run /scripts/simulate-traffic.js
 * Tùy chỉnh: -e VUS=30 -e DURATION=3m -e BASE_URL=http://localhost:8080
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, activeProducts, customerEmail, json, login, pick } from './lib.js';

const ordersPlaced = new Counter('orders_placed');
const orderLatency = new Trend('order_create_latency', true);

export const options = {
  scenarios: {
    shoppers: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 20) },
        { duration: __ENV.DURATION || '2m', target: Number(__ENV.VUS || 20) },
        { duration: '20s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
    order_create_latency: ['p(95)<1000'],
  },
};

export function setup() {
  const products = activeProducts().filter((p) => !p.sku.startsWith('FLS-'));
  if (products.length === 0) throw new Error('Chưa có sản phẩm — hãy chờ product-service seed dữ liệu');
  return { productIds: products.map((p) => p.id) };
}

let token = null; // mỗi VU đăng nhập một lần và dùng lại token

export default function (data) {
  if (!token) {
    token = login(customerEmail(__VU));
    if (!token) {
      sleep(1);
      return;
    }
  }

  // Duyệt sản phẩm
  http.get(`${BASE_URL}/api/products?page=${Math.floor(Math.random() * 2)}&size=12`, { tags: { name: 'list-products' } });
  const productId = pick(data.productIds);
  http.get(`${BASE_URL}/api/products/${productId}`, { tags: { name: 'product-detail' } });
  sleep(Math.random() * 0.5);

  // Thêm vào giỏ 1-3 sản phẩm
  const lines = {};
  const n = 1 + Math.floor(Math.random() * 3);
  for (let i = 0; i < n; i++) {
    const id = pick(data.productIds);
    const qty = 1 + Math.floor(Math.random() * 2);
    const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ productId: id, quantity: qty }),
      { ...json(token), tags: { name: 'add-to-cart' } });
    check(res, { 'add to cart ok': (r) => r.status === 200 || r.status === 400 });
    if (res.status === 200) lines[id] = (lines[id] || 0) + qty;
  }
  if (Math.random() < 0.15) {
    sleep(0.5);
    return; // bỏ giỏ hàng (để phễu chuyển đổi có ý nghĩa)
  }

  // Đặt hàng
  const items = Object.entries(lines).map(([pid, quantity]) => ({ productId: Number(pid), quantity }));
  if (items.length === 0) return;
  const paymentMethod = Math.random() < 0.1 ? 'MOCK_FAIL' : Math.random() < 0.5 ? 'MOCK_CARD' : 'MOCK_WALLET';
  const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify({
    items,
    shippingAddress: `Khách ${__VU} - 0900000000 - ${__ITER} Đường Demo, TP.HCM`,
    paymentMethod,
  }), { ...json(token), tags: { name: 'create-order' } });
  orderLatency.add(res.timings.duration);
  if (check(res, { 'order accepted (202)': (r) => r.status === 202 })) {
    ordersPlaced.add(1);
  }
  sleep(0.5 + Math.random());
}
