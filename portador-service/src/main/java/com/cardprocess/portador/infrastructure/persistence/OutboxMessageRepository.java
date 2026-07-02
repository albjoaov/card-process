package com.cardprocess.portador.infrastructure.persistence;

import com.cardprocess.portador.domain.OutboxMessage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query(value = """
            SELECT * FROM outbox_message
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockPendingBatch(@Param("batchSize") int batchSize);

    Optional<OutboxMessage> findByCorrelationId(UUID correlationId);
}
