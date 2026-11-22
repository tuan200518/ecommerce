package com.shop.analytics.monitor;

import com.shop.analytics.dashboard.DashboardState;
import com.shop.common.event.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Định kỳ đo consumer lag của mọi consumer group và throughput của các topic nghiệp vụ
 * bằng Kafka AdminClient, rồi đưa vào Dashboard.
 */
@Component
@ConditionalOnProperty(prefix = "shop.analytics.kafka-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaMonitor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaMonitor.class);

    private final KafkaAdmin kafkaAdmin;
    private final DashboardState state;
    private Admin admin;
    private Map<String, Long> previousTotals = new HashMap<>();
    private long previousSample;

    public KafkaMonitor(KafkaAdmin kafkaAdmin, DashboardState state) {
        this.kafkaAdmin = kafkaAdmin;
        this.state = state;
    }

    @Scheduled(fixedDelayString = "${shop.analytics.kafka-monitor-interval-ms:5000}", initialDelay = 5000)
    public void sample() {
        long now = System.currentTimeMillis();
        try {
            Admin client = admin();
            int brokers = client.describeCluster().nodes().get(5, TimeUnit.SECONDS).size();

            // --- Consumer lag
            Collection<ConsumerGroupListing> listings = client.listConsumerGroups().all().get(5, TimeUnit.SECONDS);
            List<String> groupIds = listings.stream().map(ConsumerGroupListing::groupId).toList();
            Map<String, ConsumerGroupDescription> descriptions = groupIds.isEmpty() ? Map.of()
                    : client.describeConsumerGroups(groupIds).all().get(5, TimeUnit.SECONDS);
            Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new HashMap<>();
            for (String g : groupIds) {
                committed.put(g, client.listConsumerGroupOffsets(g).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS));
            }
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            committed.values().forEach(m -> m.keySet().forEach(tp -> request.put(tp, OffsetSpec.latest())));

            // --- Topic throughput
            Map<String, TopicDescription> topics = client.describeTopics(Topics.ALL).allTopicNames().get(5, TimeUnit.SECONDS);
            topics.values().forEach(t -> t.partitions().forEach(p ->
                    request.put(new TopicPartition(t.name(), p.partition()), OffsetSpec.latest())));

            Map<TopicPartition, ListOffsetsResultInfo> ends = request.isEmpty() ? Map.of()
                    : client.listOffsets(request).all().get(5, TimeUnit.SECONDS);

            List<KafkaStats.GroupLag> lags = new ArrayList<>();
            for (String g : groupIds) {
                long lag = 0;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.get(g).entrySet()) {
                    ListOffsetsResultInfo end = ends.get(e.getKey());
                    if (end != null && e.getValue() != null) {
                        lag += Math.max(0, end.offset() - e.getValue().offset());
                    }
                }
                ConsumerGroupDescription d = descriptions.get(g);
                lags.add(new KafkaStats.GroupLag(g, d == null ? "UNKNOWN" : d.state().toString(),
                        d == null ? 0 : d.members().size(), lag));
            }
            lags.sort(Comparator.comparing(KafkaStats.GroupLag::groupId));

            Map<String, Long> totals = ends.entrySet().stream()
                    .filter(e -> topics.containsKey(e.getKey().topic()))
                    .collect(Collectors.groupingBy(e -> e.getKey().topic(),
                            Collectors.summingLong(e -> e.getValue().offset())));
            double seconds = previousSample == 0 ? 0 : (now - previousSample) / 1000.0;
            List<KafkaStats.TopicStat> topicStats = new ArrayList<>();
            for (TopicDescription t : topics.values()) {
                long total = totals.getOrDefault(t.name(), 0L);
                Long prev = previousTotals.get(t.name());
                double rate = prev == null || seconds <= 0 ? 0 : Math.max(0, (total - prev) / seconds);
                topicStats.add(new KafkaStats.TopicStat(t.name(), t.partitions().size(), total,
                        Math.round(rate * 10) / 10.0));
            }
            topicStats.sort(Comparator.comparing(KafkaStats.TopicStat::topic));
            previousTotals = totals;
            previousSample = now;

            state.updateKafka(new KafkaStats(now, brokers, lags, topicStats, null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Không lấy được số liệu Kafka: {}", e.getMessage());
            state.updateKafka(new KafkaStats(now, 0, List.of(), List.of(), e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        }
        return admin;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close();
        }
    }
}
