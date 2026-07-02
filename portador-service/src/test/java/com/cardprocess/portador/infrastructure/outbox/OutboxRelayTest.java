package com.cardprocess.portador.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardprocess.portador.domain.OutboxMessage;
import com.cardprocess.portador.domain.OutboxStatus;
import com.cardprocess.portador.infrastructure.messaging.SqsIssuanceSender;
import com.cardprocess.portador.infrastructure.persistence.OutboxMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxMessageRepository repository;

    @Mock
    private SqsIssuanceSender sender;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, sender, new OutboxProperties());
    }

    @Test
    void publishesPendingMessageAndMarksItPublished() {
        OutboxMessage row = OutboxMessage.pending(UUID.randomUUID(), "{\"payload\":\"a\"}");
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(row));

        relay.relayPending();

        verify(sender).send("{\"payload\":\"a\"}");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void keepsMessagePendingWithBackoffWhenSendFails() {
        OutboxMessage row = OutboxMessage.pending(UUID.randomUUID(), "{\"payload\":\"a\"}");
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(row));
        doThrow(new RuntimeException("sqs unavailable")).when(sender).send("{\"payload\":\"a\"}");

        Instant before = Instant.now();
        relay.relayPending();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("sqs unavailable");
        assertThat(row.getNextAttemptAt()).isAfter(before);
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    void continuesProcessingBatchAfterOneFailure() {
        OutboxMessage failingRow = OutboxMessage.pending(UUID.randomUUID(), "{\"payload\":\"failing\"}");
        OutboxMessage succeedingRow = OutboxMessage.pending(UUID.randomUUID(), "{\"payload\":\"succeeding\"}");
        when(repository.lockPendingBatch(anyInt())).thenReturn(List.of(failingRow, succeedingRow));
        doThrow(new RuntimeException("sqs unavailable")).when(sender).send("{\"payload\":\"failing\"}");

        relay.relayPending();

        verify(sender).send("{\"payload\":\"succeeding\"}");
        assertThat(failingRow.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(succeedingRow.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }
}
