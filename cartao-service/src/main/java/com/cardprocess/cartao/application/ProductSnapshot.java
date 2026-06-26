package com.cardprocess.cartao.application;

import java.util.UUID;

public record ProductSnapshot(UUID id, String name, String status) {

    public boolean isActive() {
        return "ATIVO".equals(status);
    }
}
