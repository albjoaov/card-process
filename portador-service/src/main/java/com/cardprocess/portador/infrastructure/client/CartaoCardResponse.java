package com.cardprocess.portador.infrastructure.client;

import java.util.UUID;

public record CartaoCardResponse(
        UUID id,
        UUID cardholderId,
        String maskedNumber,
        String status,
        ProductPayload product) {

    public record ProductPayload(UUID id, String name, String status) {
    }
}
