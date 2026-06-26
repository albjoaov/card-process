package com.cardprocess.cartao.application;

import com.cardprocess.cartao.domain.Card;
import com.cardprocess.cartao.domain.CardStatus;
import com.cardprocess.cartao.domain.DomainExceptions.CardNotFoundException;
import com.cardprocess.cartao.infrastructure.persistence.CardRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardQueryService {

    private final CardRepository repository;
    private final ProductGateway productGateway;

    public CardQueryService(CardRepository repository, ProductGateway productGateway) {
        this.repository = repository;
        this.productGateway = productGateway;
    }

    @Transactional(readOnly = true)
    public CardWithProduct getById(UUID id) {
        return enrich(repository.findById(id)
                .orElseThrow(() -> new CardNotFoundException(id.toString())));
    }

    @Transactional(readOnly = true)
    public CardWithProduct getByCardholder(UUID cardholderId) {
        return enrich(repository.findByCardholderId(cardholderId)
                .orElseThrow(() -> new CardNotFoundException("cardholder " + cardholderId)));
    }

    @Transactional
    public CardWithProduct updateStatus(UUID id, CardStatus status) {
        Card card = repository.findById(id)
                .orElseThrow(() -> new CardNotFoundException(id.toString()));
        card.changeStatus(status);
        return enrich(card);
    }

    private CardWithProduct enrich(Card card) {
        ProductSnapshot product = productGateway.getProduct(card.getProductId()).orElse(null);
        return new CardWithProduct(card, product);
    }
}
