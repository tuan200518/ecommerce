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
  UNDER_REVIEW: 'Đang xác minh',
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

export const SHIPMENT_STATUS_LABEL: Record<string, string> = {
  CREATED: 'Chờ đóng gói',
  PACKED: 'Đã đóng gói',
  HANDED_OVER: 'Đã bàn giao ĐVVC',
  IN_TRANSIT: 'Đang giao',
  DELIVERED: 'Đã giao',
  CANCELLED: 'Đã hủy',
};

export const RISK_LEVEL_LABEL: Record<string, string> = {
  LOW: 'Thấp',
  MEDIUM: 'Trung bình',
  HIGH: 'Cao',
};

export const RISK_STATUS_LABEL: Record<string, string> = {
  AUTO_APPROVED: 'Tự động chấp nhận',
  PENDING_REVIEW: 'Chờ xác minh',
  APPROVED: 'Đã chấp nhận',
  REJECTED: 'Đã từ chối',
  CLOSED: 'Đã đóng',
};

export const POINT_TYPE_LABEL: Record<string, string> = {
  EARN: 'Tích điểm đơn hàng',
  FIRST_ORDER_BONUS: 'Nhiệm vụ đơn đầu tiên',
  REFERRAL_BONUS: 'Thưởng giới thiệu',
  REVIEW_BONUS: 'Thưởng đánh giá',
  REVERSAL: 'Trừ điểm',
  REDEMPTION: 'Đổi thưởng',
};

export const stars = (rating: number) => '★'.repeat(Math.round(rating)) + '☆'.repeat(5 - Math.round(rating));

export const VOUCHER_TYPE_LABEL: Record<string, string> = {
  PERCENT: 'Giảm theo %',
  FIXED: 'Giảm số tiền',
  FREE_SHIPPING: 'Miễn phí vận chuyển',
};

/** "-10% (tối đa 50.000 ₫)", "-20.000 ₫", "Free ship" */
export function voucherValueText(v: { type: string; value: number; maxDiscount?: number | null }): string {
  if (v.type === 'FREE_SHIPPING') return 'Free ship';
  if (v.type === 'PERCENT') return `-${Number(v.value)}%` + (v.maxDiscount ? ` (tối đa ${money(v.maxDiscount)})` : '');
  return `-${money(v.value)}`;
}

export const AUDIT_ACTION_LABEL: Record<string, string> = {
  LOGIN_SUCCEEDED: 'Đăng nhập thành công',
  LOGIN_FAILED: 'Đăng nhập thất bại',
  LOGIN_BLOCKED: 'Đăng nhập bị chặn',
  ACCOUNT_LOCKED: 'Tạm khóa tài khoản',
  USER_ROLES_CHANGED: 'Đổi vai trò',
  USER_ENABLED: 'Mở khóa tài khoản',
  USER_DISABLED: 'Khóa tài khoản',
  PRODUCT_CREATED: 'Tạo sản phẩm',
  PRODUCT_UPDATED: 'Sửa sản phẩm',
  PRODUCT_ACTIVATED: 'Mở bán sản phẩm',
  PRODUCT_DISCONTINUED: 'Ngừng bán sản phẩm',
  CATEGORY_CREATED: 'Tạo danh mục',
  CATEGORY_UPDATED: 'Sửa danh mục',
  CATEGORY_DELETED: 'Xóa danh mục',
  STOCK_RESTOCKED: 'Nhập kho',
  STOCK_THRESHOLD_CHANGED: 'Đổi ngưỡng tồn kho',
  ORDER_CANCELLED_BY_STAFF: 'Nhân viên hủy đơn',
  RISK_APPROVED: 'Duyệt đơn rủi ro',
  RISK_REJECTED: 'Từ chối đơn rủi ro',
  SHIPMENT_STATUS_UPDATED: 'Cập nhật vận đơn',
  SHIPMENT_CARRIER_CHANGED: 'Đổi đơn vị vận chuyển',
  REVIEW_HIDDEN: 'Ẩn đánh giá',
  REVIEW_SHOWN: 'Hiện đánh giá',
  FLASH_SALE_CREATED: 'Tạo flash sale',
  FLASH_SALE_CANCELLED: 'Hủy flash sale',
  VOUCHER_CREATED: 'Tạo voucher',
  VOUCHER_ENABLED: 'Bật voucher',
  VOUCHER_DISABLED: 'Tắt voucher',
};
