package com.cardprocess.cartao.application;

import com.cardprocess.cartao.domain.DomainExceptions.ProductNotActiveException;
import com.cardprocess.cartao.domain.DomainExceptions.ProductNotFoundException;
import com.cardprocess.cartao.domain.DomainExceptions.ProductServiceUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProductGateway {

    private static final Logger log = LoggerFactory.getLogger(ProductGateway.class);

    private final ProductCache cache;
    private final ProductOrigin origin;

    public ProductGateway(ProductCache cache, ProductOrigin origin) {
        this.cache = cache;
        this.origin = origin;
    }

    public ProductSnapshot requireActiveProduct(UUID productId) {
        ProductSnapshot product = origin.fetch(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (!product.isActive()) {
            throw new ProductNotActiveException(productId);
        }
        cache.put(product);
        return product;
    }

    public Optional<ProductSnapshot> getProduct(UUID productId) {
        Optional<ProductSnapshot> cached = cache.find(productId);
        if (cached.isPresent()) {
            log.debug("Product cache hit productId={}", productId);
            return cached;
        }
        log.debug("Product cache miss productId={}", productId);
        try {
            Optional<ProductSnapshot> fromOrigin = origin.fetch(productId);
            fromOrigin.ifPresent(cache::put);
            return fromOrigin;
        } catch (ProductServiceUnavailableException unavailable) {
            log.warn("Product enrichment degraded productId={} reason={}", productId, unavailable.getMessage());
            return Optional.empty();
        }
    }
}
