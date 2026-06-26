package com.cardprocess.cartao.infrastructure.messaging;

import com.cardprocess.cartao.application.CardIssuanceService;
import com.cardprocess.shared.messaging.IssuanceMessage;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SqsIssuanceConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsIssuanceConsumer.class);

    private final CardIssuanceService cardIssuanceService;

    public SqsIssuanceConsumer(CardIssuanceService cardIssuanceService) {
        this.cardIssuanceService = cardIssuanceService;
    }

    @SqsListener("${cardprocess.issuance.queue}")
    public void onIssuanceMessage(IssuanceMessage message) {
        log.info("Issuance message received correlationId={} cardholderId={} productId={}",
                message.correlationId(), message.cardholderId(), message.productId());
        cardIssuanceService.issue(message);
    }
}
