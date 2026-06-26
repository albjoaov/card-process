package com.cardprocess.cartao.web.dto;

import com.cardprocess.cartao.domain.CardStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateCardStatusRequest(@NotNull CardStatus status) {
}
