package com.cardprocess.produto.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardprocess.produto.domain.Product;
import com.cardprocess.produto.domain.ProductNotFoundException;
import com.cardprocess.produto.domain.ProductStatus;
import com.cardprocess.produto.infrastructure.persistence.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repository;

    @InjectMocks
    private ProductService service;

    @Test
    void createPersistsActiveProduct() {
        when(repository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product created = service.create("Black");

        assertThat(created.getName()).isEqualTo("Black");
        assertThat(created.getStatus()).isEqualTo(ProductStatus.ATIVO);
        verify(repository).save(any(Product.class));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void cancelTransitionsStatusToCancelled() {
        UUID id = UUID.randomUUID();
        Product product = Product.create("Gold");
        when(repository.findById(id)).thenReturn(Optional.of(product));

        Product cancelled = service.cancel(id);

        assertThat(cancelled.getStatus()).isEqualTo(ProductStatus.CANCELADO);
        assertThat(cancelled.isActive()).isFalse();
    }
}
