package com.cardprocess.cartao.infrastructure.client;

import java.util.UUID;

public record ProdutoProductResponse(UUID id, String name, String status) {
}
