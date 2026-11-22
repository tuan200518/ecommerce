package com.shop.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình chung (prefix "shop") cho các microservice. */
@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    private final Kafka kafka = new Kafka();
    private final Outbox outbox = new Outbox();

    public Kafka getKafka() {
        return kafka;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public static class Kafka {
        /** Tự tạo các topic nghiệp vụ và topic DLT khi khởi động. */
        private boolean createTopics = true;
        private int partitions = 3;
        private short replicas = 1;
        /** Số lần retry trước khi đẩy sự kiện vào DLT. */
        private int maxRetries = 3;
        private long retryInitialIntervalMs = 500;
        private double retryMultiplier = 2.0;

        public boolean isCreateTopics() {
            return createTopics;
        }

        public void setCreateTopics(boolean createTopics) {
            this.createTopics = createTopics;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public short getReplicas() {
            return replicas;
        }

        public void setReplicas(short replicas) {
            this.replicas = replicas;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryInitialIntervalMs() {
            return retryInitialIntervalMs;
        }

        public void setRetryInitialIntervalMs(long retryInitialIntervalMs) {
            this.retryInitialIntervalMs = retryInitialIntervalMs;
        }

        public double getRetryMultiplier() {
            return retryMultiplier;
        }

        public void setRetryMultiplier(double retryMultiplier) {
            this.retryMultiplier = retryMultiplier;
        }
    }

    public static class Outbox {
        private boolean relayEnabled = true;
        private long pollIntervalMs = 250;
        private int batchSize = 200;

        public boolean isRelayEnabled() {
            return relayEnabled;
        }

        public void setRelayEnabled(boolean relayEnabled) {
            this.relayEnabled = relayEnabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
