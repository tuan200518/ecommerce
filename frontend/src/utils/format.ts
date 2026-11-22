const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
const num = new Intl.NumberFormat('vi-VN');

export const money = (v: number | null | undefined) => vnd.format(Number(v ?? 0));
export const number = (v: number | null | undefined) => num.format(Number(v ?? 0));

export function dateTime(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const d = new Date(v);
  return d.toLocaleString('vi-VN', { hour12: false });
}

export function timeOnly(v: number): string {
  return new Date(v).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

export const ORDER_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Chờ giữ hàng',
  AWAITING_PAYMENT: 'Chờ thanh toán',
  CONFIRMED: 'Đã xác nhận',
  SHIPPING: 'Đang giao',
  DELIVERED: 'Đã giao',
  CANCELLED: 'Đã hủy',
};

export const PAYMENT_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Đang xử lý',
  SUCCEEDED: 'Thành công',
  FAILED: 'Thất bại',
  REFUNDED: 'Đã hoàn tiền',
};

export const PAYMENT_METHOD_LABEL: Record<string, string> = {
  MOCK_CARD: 'Thẻ (mô phỏng)',
  MOCK_WALLET: 'Ví điện tử (mô phỏng)',
  MOCK_FAIL: 'Thẻ bị từ chối (kiểm thử)',
};

export const ROLE_LABEL: Record<string, string> = {
  CUSTOMER: 'Khách hàng',
  ADMIN: 'Quản trị',
  PRODUCT_MANAGER: 'QL sản phẩm',
  WAREHOUSE_MANAGER: 'QL kho',
  ANALYST: 'Phân tích',
};
