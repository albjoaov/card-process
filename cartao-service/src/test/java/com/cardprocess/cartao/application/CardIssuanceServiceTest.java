package com.cardprocess.cartao.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardprocess.cartao.domain.Card;
import com.cardprocess.cartao.domain.DomainExceptions.ProductNotActiveException;
import com.cardprocess.cartao.infrastructure.persistence.CardRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CardIssuanceServiceTest {

    @Mock
    private CardRepository repository;

    @Mock
    private ProductGateway productGateway;

    private CardIssuanceService service;

    private final UUID cardholderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final IssuanceMessage message = new IssuanceMessage(cardholderId, productId, correlationId);

    @BeforeEach
    void setUp() {
        service = new CardIssuanceService(repository, productGateway, new CardNumberGenerator());
    }

    @Test
    void issuesCardWhenProductIsActive() {
        when(repository.existsByCorrelationId(correlationId)).thenReturn(false);
        when(repository.existsByCardholderId(cardholderId)).thenReturn(false);
        when(productGateway.requireActiveProduct(productId))
                .thenReturn(new ProductSnapshot(productId, "Black", "ATIVO"));
        when(repository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.issue(message);

        verify(repository).save(any(Card.class));
    }

    @Test
    void skipsIssuanceWhenAlreadyProcessed() {
        when(repository.existsByCorrelationId(correlationId)).thenReturn(true);

        service.issue(message);

        verify(repository, never()).save(any());
        verifyNoInteractions(productGateway);
    }

    @Test
    void doesNotPersistCardWhenProductIsNotActive() {
        when(repository.existsByCorrelationId(correlationId)).thenReturn(false);
        when(repository.existsByCardholderId(cardholderId)).thenReturn(false);
        when(productGateway.requireActiveProduct(productId))
                .thenThrow(new ProductNotActiveException(productId));

        assertThatThrownBy(() -> service.issue(message)).isInstanceOf(ProductNotActiveException.class);

        verify(repository, never()).save(any());
    }
}
