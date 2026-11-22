-- Bảng dữ liệu tổng hợp được tạo từ luồng sự kiện (Dashboard/Report không truy vấn bảng nghiệp vụ)
CREATE TABLE IF NOT EXISTS sales_aggregates (
    window_size    VARCHAR(8)     NOT NULL,
    window_start   TIMESTAMP      NOT NULL,
    window_end     TIMESTAMP      NOT NULL,
    revenue        NUMERIC(18, 2) NOT NULL,
    payments_count BIGINT         NOT NULL,
    updated_at     TIMESTAMP      NOT NULL,
    PRIMARY KEY (window_size, window_start)
);

CREATE TABLE IF NOT EXISTS product_statistics (
    product_id    BIGINT PRIMARY KEY,
    product_name  VARCHAR(255),
    quantity_sold BIGINT         NOT NULL,
    revenue       NUMERIC(18, 2) NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);

CREATE TABLE IF NOT EXISTS event_statistics (
    metric     VARCHAR(64)  NOT NULL,
    metric_key VARCHAR(128) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    event_count  BIGINT       NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (metric, metric_key)
);

CREATE TABLE IF NOT EXISTS analytics_alerts (
    alert_id   VARCHAR(64) PRIMARY KEY,
    type       VARCHAR(64)   NOT NULL,
    severity   VARCHAR(16)   NOT NULL,
    title      VARCHAR(255)  NOT NULL,
    message    VARCHAR(1000),
    created_at TIMESTAMP     NOT NULL
);
