/**
 * Kịch bản B – Flash Sale: nhiều khách đặt CÙNG một sản phẩm số lượng ít trong vài giây.
 * Kỳ vọng: số đơn được xác nhận ≤ tồn kho ban đầu, tồn kho không bao giờ âm (không bán vượt).
 *
 * Chạy:  k6 run -e BUYERS=200 scripts/k6/flash-sale.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, customerEmail, json, login } from './lib.js';

const accepted = new Counter('flash_orders_accepted');
const BUYERS = Number(__ENV.BUYERS || 200);
const SKU = __ENV.SKU || 'FLS-001';

export const options = {
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '2m' },
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/api/products?q=${SKU}&size=5`);
  const product = res.json('content').find((p) => p.sku === SKU);
  if (!product) throw new Error(`Không tìm thấy sản phẩm ${SKU}`);
  const stock = http.get(`${BASE_URL}/api/inventory/availability?productIds=${product.id}`).json()[0];
  console.log(`Flash sale "${product.name}" (#${product.id}) – tồn kho ban đầu: ${stock.available}, người mua: ${BUYERS}`);
  return { productId: product.id, initial: stock.available };
}

export default function (data) {
  const token = login(customerEmail(__VU));
  if (!token) return;
  sleep(Math.random() * 0.2); // mọi người "bấm mua" gần như cùng lúc
  const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify({
    items: [{ productId: data.productId, quantity: 1 }],
    shippingAddress: `Flash buyer ${__VU} - 0900000000 - TP.HCM`,
    paymentMethod: 'MOCK_CARD',
  }), json(token));
  if (check(res, { 'order accepted': (r) => r.status === 202 })) accepted.add(1);
}

export function teardown(data) {
  sleep(15); // chờ Saga (giữ hàng → thanh toán) xử lý xong qua Kafka
  const adminToken = login('admin@shop.local', 'Admin@123');
  const inv = http.get(`${BASE_URL}/api/inventory?size=200`, json(adminToken)).json('content')
    .find((i) => i.productId === data.productId);
  const sold = data.initial - inv.available - inv.reserved;
  console.log(`Kết quả: khả dụng=${inv.available}, đang giữ=${inv.reserved}, đã bán=${sold} / tồn ban đầu ${data.initial}`);
  check(inv, {
    'không âm kho': (i) => i.available >= 0,
    'không bán vượt tồn kho': () => sold + inv.reserved <= data.initial,
  });
}
