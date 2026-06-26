package com.cardprocess.cartao.infrastructure.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cardprocess.product.cache")
public class ProductCacheProperties {

    private Duration ttl = Duration.ofMinutes(10);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
