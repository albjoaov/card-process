package com.cardprocess.cartao.domain;

import java.util.UUID;

public final class DomainExceptions {

    private DomainExceptions() {
    }

    public static class CardNotFoundException extends RuntimeException {
        public CardNotFoundException(String reference) {
            super("Card not found: " + reference);
        }
    }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(UUID productId) {
            super("Product not found: " + productId);
        }
    }

    public static class ProductNotActiveException extends RuntimeException {
        public ProductNotActiveException(UUID productId) {
            super("Product is not active: " + productId);
        }
    }

    public static class ProductServiceUnavailableException extends RuntimeException {
        public ProductServiceUnavailableException(String detail, Throwable cause) {
            super("Produto Service unavailable: " + detail, cause);
        }
    }
}
