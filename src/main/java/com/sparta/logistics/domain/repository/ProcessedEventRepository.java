package com.sparta.logistics.domain.repository;

import com.sparta.logistics.domain.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query(value = """
            INSERT INTO p_processed_events (event_id, event_type, consumer, processed_at)
            VALUES (:eventId, :eventType, :consumer, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("consumer") String consumer,
            @Param("processedAt") Instant processedAt
    );
}
