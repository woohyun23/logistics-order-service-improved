package com.sparta.logistics.common.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPerformanceMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OrderPerformanceMetrics metrics = new OrderPerformanceMetrics(meterRegistry);

    @Test
    @DisplayName("외부 조회 Metric은 제한된 target과 source 태그로 기록한다")
    void recordExternalQuery_recordsLowCardinalityTags() {
        metrics.recordExternalQuery("product", "live");
        metrics.recordExternalQuery("product", "live");

        assertThat(meterRegistry.get("order.external.query")
                .tags("target", "product", "source", "live")
                .counter()
                .count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Outbox 선점 건수와 배치 크기를 함께 기록한다")
    void recordOutboxClaimed_recordsCounterAndBatchSize() {
        metrics.recordOutboxClaimed(50);
        metrics.recordOutboxClaimed(10);

        assertThat(meterRegistry.get("order.outbox.claimed").counter().count()).isEqualTo(60.0);
        assertThat(meterRegistry.get("order.outbox.claim.batch.size").summary().count()).isEqualTo(2L);
        assertThat(meterRegistry.get("order.outbox.claim.batch.size").summary().totalAmount()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("작업 시간은 operation과 result 태그로 기록한다")
    void stopTimer_recordsOperationDuration() {
        Timer.Sample sample = metrics.startTimer();

        metrics.stopTimer(sample, "consumer_event", "processed");

        assertThat(meterRegistry.get("order.operation.duration")
                .tags("operation", "consumer_event", "result", "processed")
                .timer()
                .count()).isEqualTo(1L);
    }
}
