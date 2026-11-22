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
  totalAmount: number;
  paymentMethod: string;
  shippingAddress: string;
  cancelReason?: string | null;
  createdAt: string;
  updatedAt: string;
  items: OrderItem[];
  history?: OrderHistory[] | null;
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
