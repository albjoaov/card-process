package com.cardprocess.portador.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardprocess.portador.application.IssuancePublisher;
import com.cardprocess.portador.domain.OutboxMessage;
import com.cardprocess.portador.infrastructure.persistence.OutboxMessageRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OutboxIssuancePublisher implements IssuancePublisher {

    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxIssuancePublisher(OutboxMessageRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(IssuanceMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox publishing requires an active transaction to guarantee atomicity");
        }
        repository.save(OutboxMessage.pending(message.correlationId(), serialize(message)));
    }

    private String serialize(IssuanceMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize issuance message correlationId=" + message.correlationId(), e);
        }
    }
}
