package com.cardprocess.shared.messaging;

import java.util.UUID;

public record IssuanceMessage(UUID cardholderId, UUID productId, UUID correlationId) {
}
