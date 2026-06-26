package com.cardprocess.cartao.application;

import java.util.Optional;
import java.util.UUID;

public interface ProductCache {

    Optional<ProductSnapshot> find(UUID productId);

    void put(ProductSnapshot product);
}
