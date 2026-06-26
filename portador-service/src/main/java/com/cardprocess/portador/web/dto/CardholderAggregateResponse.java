package com.cardprocess.portador.web.dto;

import com.cardprocess.portador.application.AggregationService.CardholderAggregate;
import com.cardprocess.portador.application.CardView;
import java.util.UUID;

public record CardholderAggregateResponse(
        CardholderResponse cardholder,
        CardSummary card,
        ProductSummary product) {

    public static CardholderAggregateResponse from(CardholderAggregate aggregate) {
        CardView card = aggregate.card();
        CardSummary cardSummary = card == null ? null
                : new CardSummary(card.id(), card.maskedNumber(), card.status());
        ProductSummary productSummary = card == null || card.product() == null ? null
                : new ProductSummary(card.product().id(), card.product().name(), card.product().status());
        return new CardholderAggregateResponse(
                CardholderResponse.from(aggregate.cardholder()),
                cardSummary,
                productSummary);
    }

    public record CardSummary(UUID id, String maskedNumber, String status) {
    }

    public record ProductSummary(UUID id, String name, String status) {
    }
}
