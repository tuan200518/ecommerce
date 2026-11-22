import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const PASSWORD = __ENV.CUSTOMER_PASSWORD || 'Customer@123';
export const CUSTOMER_COUNT = Number(__ENV.CUSTOMER_COUNT || 50);

export function json(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return { headers };
}

export function login(email, password = PASSWORD) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password }), json());
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json('accessToken') : null;
}

export function customerEmail(vu) {
  // customer1..customerN@shop.local được Auth Service tạo sẵn (SEED_CUSTOMERS)
  return `customer${((vu - 1) % CUSTOMER_COUNT) + 1}@shop.local`;
}

export function activeProducts() {
  const res = http.get(`${BASE_URL}/api/products?size=100`);
  check(res, { 'list products 200': (r) => r.status === 200 });
  return res.json('content').filter((p) => p.active);
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
