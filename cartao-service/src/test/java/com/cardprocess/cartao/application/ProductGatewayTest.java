package com.cardprocess.cartao.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardprocess.cartao.domain.DomainExceptions.ProductNotActiveException;
import com.cardprocess.cartao.domain.DomainExceptions.ProductNotFoundException;
import com.cardprocess.cartao.domain.DomainExceptions.ProductServiceUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductGatewayTest {

    @Mock
    private ProductCache cache;

    @Mock
    private ProductOrigin origin;

    @InjectMocks
    private ProductGateway gateway;

    private final UUID productId = UUID.randomUUID();
    private final ProductSnapshot active = new ProductSnapshot(productId, "Black", "ATIVO");

    @Test
    void getProductReturnsCachedValueWithoutCallingOrigin() {
        when(cache.find(productId)).thenReturn(Optional.of(active));

        assertThat(gateway.getProduct(productId)).contains(active);
        verifyNoInteractions(origin);
    }

    @Test
    void getProductFallsBackToOriginAndWarmsCacheOnMiss() {
        when(cache.find(productId)).thenReturn(Optional.empty());
        when(origin.fetch(productId)).thenReturn(Optional.of(active));

        assertThat(gateway.getProduct(productId)).contains(active);
        verify(cache).put(active);
    }

    @Test
    void getProductDegradesToEmptyWhenOriginUnavailable() {
        when(cache.find(productId)).thenReturn(Optional.empty());
        when(origin.fetch(productId)).thenThrow(new ProductServiceUnavailableException("down", null));

        assertThat(gateway.getProduct(productId)).isEmpty();
    }

    @Test
    void requireActiveProductReturnsAndWarmsCache() {
        when(origin.fetch(productId)).thenReturn(Optional.of(active));

        assertThat(gateway.requireActiveProduct(productId)).isEqualTo(active);
        verify(cache).put(active);
    }

    @Test
    void requireActiveProductThrowsWhenProductMissing() {
        when(origin.fetch(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.requireActiveProduct(productId))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void requireActiveProductThrowsWhenProductCancelled() {
        when(origin.fetch(productId)).thenReturn(Optional.of(new ProductSnapshot(productId, "Black", "CANCELADO")));

        assertThatThrownBy(() -> gateway.requireActiveProduct(productId))
                .isInstanceOf(ProductNotActiveException.class);
    }
}
