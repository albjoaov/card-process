package com.cardprocess.produto.application;

import com.cardprocess.produto.domain.Product;
import com.cardprocess.produto.domain.ProductNotFoundException;
import com.cardprocess.produto.infrastructure.persistence.ProductRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Product create(String name) {
        return repository.save(Product.create(name));
    }

    @Transactional(readOnly = true)
    public Product getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public Product cancel(UUID id) {
        Product product = getById(id);
        product.cancel();
        return product;
    }
}
