package com.cardprocess.portador.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardprocess.portador.domain.OutboxMessage;
import com.cardprocess.portador.domain.OutboxStatus;
import com.cardprocess.portador.infrastructure.persistence.OutboxMessageRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OutboxIssuancePublisherTest {

    @Mock
    private OutboxMessageRepository repository;

    private OutboxIssuancePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxIssuancePublisher(repository, new ObjectMapper());
    }

    @Test
    void persistsPendingOutboxRowInsideActiveTransaction() {
        IssuanceMessage message = new IssuanceMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(message);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(captor.capture());
        OutboxMessage saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getCorrelationId()).isEqualTo(message.correlationId());
        assertThat(saved.getPayload()).contains(message.cardholderId().toString());
        assertThat(saved.getPayload()).contains(message.productId().toString());
    }

    @Test
    void rejectsPublishingOutsideTransaction() {
        IssuanceMessage message = new IssuanceMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        verifyNoInteractions(repository);
    }
}
