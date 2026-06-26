package com.cardprocess.cartao.web.dto;

import com.cardprocess.cartao.application.CardWithProduct;
import com.cardprocess.cartao.application.ProductSnapshot;
import com.cardprocess.cartao.domain.Card;
import com.cardprocess.cartao.domain.CardStatus;
import java.time.Instant;
import java.util.UUID;

public record CardResponse(
        UUID id,
        UUID cardholderId,
        String maskedNumber,
        CardStatus status,
        ProductSummary product,
        Instant createdAt,
        Instant updatedAt) {

    public static CardResponse from(CardWithProduct cardWithProduct) {
        Card card = cardWithProduct.card();
        ProductSnapshot product = cardWithProduct.product();
        ProductSummary summary = product == null ? null
                : new ProductSummary(product.id(), product.name(), product.status());
        return new CardResponse(
                card.getId(),
                card.getCardholderId(),
                card.getMaskedNumber(),
                card.getStatus(),
                summary,
                card.getCreatedAt(),
                card.getUpdatedAt());
    }

    public record ProductSummary(UUID id, String name, String status) {
    }
}
