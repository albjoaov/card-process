package com.cardprocess.produto.infrastructure.persistence;

import com.cardprocess.produto.domain.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
}
