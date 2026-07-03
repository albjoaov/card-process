package com.cardprocess.portador.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardprocess.portador.domain.Cardholder;
import com.cardprocess.portador.domain.DomainExceptions.DuplicateCpfException;
import com.cardprocess.portador.infrastructure.persistence.CardholderRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CardholderServiceTest {

    @Mock
    private CardholderRepository repository;

    @Mock
    private IssuancePublisher issuancePublisher;

    @InjectMocks
    private CardholderService service;

    @Test
    void registerPersistsCardholderAndPublishesIssuance() {
        UUID productId = UUID.randomUUID();
        when(repository.existsByCpf("39053344705")).thenReturn(false);
        when(repository.saveAndFlush(any(Cardholder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register("Maria Silva", "39053344705", LocalDate.of(1990, 5, 12), productId);

        ArgumentCaptor<IssuanceMessage> captor = ArgumentCaptor.forClass(IssuanceMessage.class);
        verify(issuancePublisher).publish(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(productId);
        assertThat(captor.getValue().correlationId()).isNotNull();
    }

    @Test
    void registerNormalizesFormattedCpfBeforePersisting() {
        when(repository.existsByCpf("39053344705")).thenReturn(false);
        when(repository.saveAndFlush(any(Cardholder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register("Maria Silva", "390.533.447-05", LocalDate.of(1990, 5, 12), UUID.randomUUID());

        ArgumentCaptor<Cardholder> captor = ArgumentCaptor.forClass(Cardholder.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCpf()).isEqualTo("39053344705");
    }

    @Test
    void registerRejectsDuplicateCpfAndDoesNotPublish() {
        when(repository.existsByCpf("39053344705")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                "Maria Silva", "39053344705", LocalDate.of(1990, 5, 12), UUID.randomUUID()))
                .isInstanceOf(DuplicateCpfException.class)
                .hasMessageNotContaining("39053344705");

        verify(issuancePublisher, never()).publish(any());
    }
}
