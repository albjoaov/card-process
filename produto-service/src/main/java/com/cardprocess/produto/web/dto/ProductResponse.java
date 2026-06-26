package com.cardprocess.produto.web.dto;

import com.cardprocess.produto.domain.Product;
import com.cardprocess.produto.domain.ProductStatus;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
