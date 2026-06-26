package com.cardprocess.cartao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "card")
public class Card extends BaseAuditEntity {

    @Column(name = "cardholder_id", nullable = false, unique = true)
    private UUID cardholderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "masked_number", nullable = false, length = 19)
    private String maskedNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "correlation_id", nullable = false, unique = true)
    private UUID correlationId;

    protected Card() {
    }

    private Card(UUID cardholderId, UUID productId, String maskedNumber, UUID correlationId, CardStatus status) {
        this.cardholderId = cardholderId;
        this.productId = productId;
        this.maskedNumber = maskedNumber;
        this.correlationId = correlationId;
        this.status = status;
    }

    public static Card issue(UUID cardholderId, UUID productId, String maskedNumber, UUID correlationId) {
        return new Card(cardholderId, productId, maskedNumber, correlationId, CardStatus.ATIVO);
    }

    public void changeStatus(CardStatus newStatus) {
        this.status = newStatus;
    }

    public UUID getCardholderId() {
        return cardholderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getMaskedNumber() {
        return maskedNumber;
    }

    public CardStatus getStatus() {
        return status;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }
}
