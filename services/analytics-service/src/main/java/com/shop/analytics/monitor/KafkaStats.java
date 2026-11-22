package com.shop.analytics.monitor;

import java.util.List;

/** Chỉ số vận hành Kafka (mục 4.3 #10: theo dõi hiệu suất Kafka). */
public record KafkaStats(long sampledAt, int brokers, List<GroupLag> consumerGroups, List<TopicStat> topics,
                         String error) {

    public record GroupLag(String groupId, String state, int members, long lag) {
    }

    public record TopicStat(String topic, int partitions, long messages, double messagesPerSecond) {
    }
}
