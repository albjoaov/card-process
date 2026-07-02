package com.cardprocess.portador.infrastructure.outbox;

import com.cardprocess.portador.domain.OutboxMessage;
import com.cardprocess.portador.infrastructure.messaging.SqsIssuanceSender;
import com.cardprocess.portador.infrastructure.persistence.OutboxMessageRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMessageRepository repository;
    private final SqsIssuanceSender sender;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxMessageRepository repository, SqsIssuanceSender sender,
                       OutboxProperties properties) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cardprocess.outbox.poll-interval}")
    @Transactional
    public void relayPending() {
        for (OutboxMessage message : repository.lockPendingBatch(properties.getBatchSize())) {
            try {
                sender.send(message.getPayload());
                message.markPublished();
                log.info("Outbox message published correlationId={} attempts={}",
                        message.getCorrelationId(), message.getAttempts());
            } catch (Exception failure) {
                message.recordFailure(failure.toString(),
                        Instant.now().plus(backoffFor(message.getAttempts() + 1)));
                log.warn("Outbox delivery failed correlationId={} attempts={} nextAttemptAt={}",
                        message.getCorrelationId(), message.getAttempts(), message.getNextAttemptAt(), failure);
            }
        }
    }

    private Duration backoffFor(int attempts) {
        Duration backoff = properties.getInitialBackoff().multipliedBy(1L << Math.min(attempts - 1, 20));
        return backoff.compareTo(properties.getMaxBackoff()) > 0 ? properties.getMaxBackoff() : backoff;
    }
}
