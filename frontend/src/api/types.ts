export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface User {
  id: number;
  email: string;
  fullName: string;
  roles: string[];
  enabled: boolean;
  createdAt: string;
  lastLoginAt?: string | null;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Category {
  id: number;
  name: string;
  slug: string;
  description?: string;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description?: string;
  price: number;
  categoryId?: number | null;
  categoryName?: string | null;
  imageUrl?: string | null;
  images: string[];
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Availability {
  productId: number;
  available: number;
  inStock: boolean;
}

export interface CartLine {
  productId: number;
  name: string;
  imageUrl?: string | null;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  available: boolean;
}

export interface Cart {
  userId: number;
  items: CartLine[];
  totalQuantity: number;
  totalAmount: number;
}

export interface OrderItem {
  productId: number;
  productName: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  /** Giá flash sale đã áp dụng (null nếu tính giá niêm yết) */
  salePrice?: number | null;
}

export interface OrderHistory {
  fromStatus: string | null;
  toStatus: string;
  note?: string | null;
  changedAt: string;
}

export interface Order {
  id: number;
  userId: number;
  status: string;
  subtotal?: number;
  shippingFee?: number;
  totalAmount: number;
  paymentMethod: string;
  shippingAddress: string;
  cancelReason?: string | null;
  createdAt: string;
  updatedAt: string;
  items: OrderItem[];
  history?: OrderHistory[] | null;
  /** Chỉ có với nhân viên */
  riskScore?: number | null;
  riskLevel?: string | null;
  voucherCode?: string | null;
  itemDiscount?: number;
  voucherDiscount?: number;
  discount?: number;
  promotionSummary?: string | null;
  /** Promotion Service chưa định giá xong – tổng tiền có thể còn giảm */
  promotionPending?: boolean;
}

export interface Notification {
  id: number;
  type: string;
  title: string;
  message?: string;
  referenceId?: string | null;
  read: boolean;
  broadcast: boolean;
  createdAt: string;
}

export interface Address {
  id: number;
  label?: string;
  recipient: string;
  phone: string;
  line: string;
  city: string;
  isDefault: boolean;
  fullText: string;
}

export interface Profile {
  userId: number;
  email: string;
  fullName: string;
  phone?: string | null;
  dateOfBirth?: string | null;
  avatarUrl?: string | null;
  addresses: Address[];
}

export interface InventoryItem {
  productId: number;
  productName: string;
  available: number;
  reserved: number;
  lowStockThreshold: number;
  lowStock: boolean;
  updatedAt: string;
}

export interface Payment {
  id: number;
  orderId: number;
  userId: number;
  amount: number;
  method: string;
  status: string;
  failureReason?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentSummary {
  status: string;
  count: number;
  amount: number;
}

// ----------------------------------------------------------------- Analytics

export interface Point {
  t: number;
  value: number;
  count: number;
}

export interface Kpis {
  totalOrders: number;
  confirmedOrders: number;
  cancelledOrders: number;
  revenue: number;
  activeUsers: number;
  lowStockProducts: number;
  paymentSuccessRate: number;
  paymentsCompleted: number;
  paymentsFailed: number;
  cartToOrderRate: number;
  averageOrderValue: number;
  productViews: number;
  viewToOrderRate: number;
  flaggedOrders: number;
  shipmentsDelivered: number;
  reviews: number;
}

export interface ProductStat {
  productId: number;
  name: string;
  quantity: number;
  revenue: number;
}

export interface StockInfo {
  productId: number;
  name: string;
  available: number;
  reserved: number;
  threshold: number;
}

export interface Alert {
  id: string;
  type: string;
  severity: string;
  title: string;
  message: string;
  at: number;
}

export interface KafkaStats {
  sampledAt: number;
  brokers: number;
  consumerGroups: { groupId: string; state: string; members: number; lag: number }[];
  topics: { topic: string; partitions: number; messages: number; messagesPerSecond: number }[];
  error?: string | null;
}

export interface Snapshot {
  generatedAt: number;
  kpis: Kpis;
  revenuePerMinute: Point[];
  revenuePer5Minutes: Point[];
  revenuePerHour: Point[];
  ordersPerMinute: Point[];
  paymentFailuresPerMinute: Point[];
  ordersByStatus: Record<string, number>;
  topProducts: ProductStat[];
  lowStock: StockInfo[];
  counters: Record<string, number>;
  alerts: Alert[];
  kafka?: KafkaStats | null;
}

// ----------------------------------------------------------------- Ecommerce 2.0: Shipping

export interface ShipmentTimeline {
  status: string;
  note?: string | null;
  at: string;
}

export interface Shipment {
  id: number;
  orderId: number;
  userId: number;
  address?: string | null;
  orderTotal?: number | null;
  carrier: string;
  carrierName: string;
  trackingNumber: string;
  fee: number;
  status: string;
  nextStatuses: string[];
  createdAt: string;
  updatedAt: string;
  deliveredAt?: string | null;
  timeline?: ShipmentTimeline[] | null;
}

export interface Carrier {
  code: string;
  name: string;
  baseFee: number;
}

export interface ShippingSummary {
  byStatus: Record<string, number>;
  byCarrier: Record<string, number>;
  carriers: Carrier[];
}

// ----------------------------------------------------------------- Fraud

export interface RiskSignal {
  rule: string;
  points: number;
  detail: string;
}

export interface RiskCase {
  orderId: number;
  userId: number;
  totalAmount: number;
  score: number;
  level: string;
  status: string;
  signals: RiskSignal[];
  reviewer?: string | null;
  reviewNote?: string | null;
  createdAt: string;
  decidedAt?: string | null;
}

export interface FraudSummary {
  byStatus: Record<string, number>;
  byLevel: Record<string, number>;
  rules: { code: string; description: string; points: number }[];
  reviewScore: number;
  minSignalsForReview: number;
}

// ----------------------------------------------------------------- Loyalty

export interface Tier {
  code: string;
  name: string;
  minSpend: number;
  multiplier: number;
}

export interface LoyaltyAccount {
  userId: number;
  balance: number;
  lifetimeEarned: number;
  tier: Tier;
  spend12Months: number;
  nextTier?: Tier | null;
  amountToNextTier: number;
  referralCode: string;
  referred: boolean;
  referralCount: number;
  firstOrderCompleted: boolean;
}

export interface PointTransaction {
  id: number;
  type: string;
  points: number;
  referenceId: string;
  description?: string | null;
  reversed: boolean;
  createdAt: string;
}

// ----------------------------------------------------------------- Reviews

export interface Review {
  id: number;
  productId: number;
  productName?: string | null;
  userId: number;
  authorName: string;
  rating: number;
  comment?: string | null;
  images: string[];
  visible: boolean;
  moderationNote?: string | null;
  createdAt: string;
}

export interface ReviewSummary {
  productId: number;
  average: number;
  count: number;
  distribution: Record<string, number>;
}

export interface ReviewEligibility {
  canReview: boolean;
  alreadyReviewed: boolean;
  reason?: string | null;
}

export interface Answer {
  id: number;
  authorName: string;
  staff: boolean;
  content: string;
  createdAt: string;
}

export interface Question {
  id: number;
  productId: number;
  authorName: string;
  content: string;
  answered: boolean;
  createdAt: string;
  answers: Answer[];
}

// ------------------------------------------------------------------ Khuyến mãi (Promotion Service)

export interface FlashSaleItem {
  id: number;
  productId: number;
  productName: string;
  imageUrl?: string | null;
  listPrice: number;
  salePrice: number;
  discountPercent: number;
  quota: number;
  sold: number;
  remaining: number;
  perUserLimit: number;
}

export interface FlashSale {
  id: number;
  name: string;
  description?: string | null;
  startsAt: string;
  endsAt: string;
  status: 'UPCOMING' | 'ACTIVE' | 'ENDED' | 'CANCELLED';
  createdBy?: string | null;
  items: FlashSaleItem[];
}

export type VoucherType = 'PERCENT' | 'FIXED' | 'FREE_SHIPPING';

export interface PublicVoucher {
  code: string;
  description: string;
  type: VoucherType;
  value: number;
  maxDiscount?: number | null;
  minOrderAmount: number;
  endsAt: string;
  remainingUses?: number | null;
  perUserLimit: number;
}

export interface MyVoucher {
  code: string;
  description: string;
  type: VoucherType;
  value: number;
  maxDiscount?: number | null;
  minOrderAmount: number;
  endsAt: string;
  source: string;
  status: 'AVAILABLE' | 'USED' | 'EXPIRED' | 'DISABLED';
}

export interface AdminVoucher extends PublicVoucher {
  id: number;
  startsAt: string;
  usageLimit?: number | null;
  used: number;
  ownerUserId?: number | null;
  source: string;
  active: boolean;
  createdBy?: string | null;
  createdAt: string;
}

export interface QuoteLine {
  productId: number;
  name: string;
  quantity: number;
  unitPrice: number;
  salePrice: number;
  lineTotal: number;
  flashSale?: string | null;
}

export interface Quote {
  lines: QuoteLine[];
  subtotal: number;
  itemDiscount: number;
  voucherCode?: string | null;
  voucherValid: boolean;
  freeShipping: boolean;
  voucherDiscount: number;
  voucherMessage?: string | null;
  totalDiscount: number;
  merchandiseTotal: number;
  warnings: string[];
}

export interface PromotionStats {
  days: number;
  ordersWithPromotion: number;
  flashSaleDiscount: number;
  voucherDiscount: number;
  activeFlashSales: number;
  activeVouchers: number;
  topVouchers: { label: string; count: number; amount: number }[];
  flashSaleItems: FlashSaleItem[];
}

export interface Reward {
  id: string;
  name: string;
  points: number;
  voucherType: VoucherType;
  value: number;
  maxDiscount?: number | null;
  minOrderAmount: number;
  validDays: number;
}

export interface RedeemResult {
  redemptionId: string;
  rewardName: string;
  pointsSpent: number;
  balance: number;
  message: string;
}

// ------------------------------------------------------------------ Nhật ký kiểm toán (Audit Service)

export interface AuditEntry {
  id: number;
  occurredAt: string;
  source: string;
  correlationId?: string | null;
  actorId?: number | null;
  actorEmail?: string | null;
  actorRoles?: string | null;
  action: string;
  resourceType: string;
  resourceId?: string | null;
  outcome: 'SUCCESS' | 'FAILURE';
  detail?: string | null;
  clientIp?: string | null;
}

export interface AuditSummary {
  hours: number;
  total: number;
  failures: number;
  failedLogins: number;
  lockedAccounts: number;
  byAction: { key: string; count: number }[];
  bySource: { key: string; count: number }[];
  suspiciousIps: { ip: string; failures: number; accounts: number }[];
}
