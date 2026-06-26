package com.cardprocess.portador.web.dto;

import com.cardprocess.portador.domain.Cardholder;
import com.cardprocess.portador.domain.CardholderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CardholderResponse(
        UUID id,
        String name,
        String cpf,
        LocalDate birthDate,
        CardholderStatus status,
        UUID productId,
        Instant createdAt,
        Instant updatedAt) {

    public static CardholderResponse from(Cardholder cardholder) {
        return new CardholderResponse(
                cardholder.getId(),
                cardholder.getName(),
                cardholder.getCpf(),
                cardholder.getBirthDate(),
                cardholder.getStatus(),
                cardholder.getProductId(),
                cardholder.getCreatedAt(),
                cardholder.getUpdatedAt());
    }
}
