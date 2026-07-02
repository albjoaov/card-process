package com.cardprocess.cartao.application;

import com.cardprocess.cartao.domain.Card;
import com.cardprocess.cartao.infrastructure.persistence.CardRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(CardIssuanceService.class);

    private final CardRepository repository;
    private final ProductGateway productGateway;
    private final CardNumberGenerator cardNumberGenerator;

    public CardIssuanceService(CardRepository repository,
                               ProductGateway productGateway,
                               CardNumberGenerator cardNumberGenerator) {
        this.repository = repository;
        this.productGateway = productGateway;
        this.cardNumberGenerator = cardNumberGenerator;
    }

    @Transactional
    public void issue(IssuanceMessage message) {
        if (alreadyProcessed(message)) {
            log.info("Issuance skipped, card already exists correlationId={} cardholderId={}",
                    message.correlationId(), message.cardholderId());
            return;
        }

        ProductSnapshot product = productGateway.requireActiveProduct(message.productId());

        Card card = repository.save(Card.issue(
                message.cardholderId(),
                message.productId(),
                cardNumberGenerator.generateMasked(),
                message.correlationId()));
        log.info("Card issued cardId={} cardholderId={} productId={} product={}",
                card.getId(), card.getCardholderId(), product.id(), product.name());
    }

    private boolean alreadyProcessed(IssuanceMessage message) {
        return repository.existsByCorrelationId(message.correlationId())
                || repository.existsByCardholderId(message.cardholderId());
    }
}
