-- Mỗi microservice sở hữu một database riêng (database-per-service).
-- Chạy lại nhiều lần an toàn: chỉ tạo database còn thiếu. Được dùng bởi
--   * docker-entrypoint-initdb.d (lần khởi tạo volume đầu tiên), và
--   * service db-init trong docker-compose (mỗi lần "up") → volume cũ cũng có database của service mới.
SELECT 'CREATE DATABASE ' || name
FROM unnest(ARRAY[
    'auth_db', 'user_db', 'product_db', 'cart_db', 'order_db', 'inventory_db', 'payment_db',
    'notification_db', 'analytics_db',
    'fraud_db', 'loyalty_db', 'shipping_db', 'review_db',
    'promotion_db', 'audit_db'
]) AS name
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = name)
\gexec
