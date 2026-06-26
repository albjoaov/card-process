package com.cardprocess.cartao.infrastructure.cache;

import com.cardprocess.cartao.application.ProductCache;
import com.cardprocess.cartao.application.ProductSnapshot;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);
    private static final String KEY_PREFIX = "product:";

    private final RedisTemplate<String, ProductSnapshot> redisTemplate;
    private final Duration ttl;

    public RedisProductCache(RedisTemplate<String, ProductSnapshot> redisTemplate, ProductCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.getTtl();
    }

    @Override
    public Optional<ProductSnapshot> find(UUID productId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(productId)));
        } catch (RuntimeException unavailable) {
            log.warn("Redis read unavailable, degrading to cache miss productId={} reason={}",
                    productId, unavailable.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(ProductSnapshot product) {
        try {
            redisTemplate.opsForValue().set(key(product.id()), product, ttl);
        } catch (RuntimeException unavailable) {
            log.warn("Redis write unavailable, skipping cache productId={} reason={}",
                    product.id(), unavailable.getMessage());
        }
    }

    private String key(UUID productId) {
        return KEY_PREFIX + productId;
    }
}
