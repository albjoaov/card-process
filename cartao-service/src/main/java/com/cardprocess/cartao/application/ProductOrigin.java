package com.cardprocess.cartao.application;

import java.util.Optional;
import java.util.UUID;

public interface ProductOrigin {

    Optional<ProductSnapshot> fetch(UUID productId);
}
