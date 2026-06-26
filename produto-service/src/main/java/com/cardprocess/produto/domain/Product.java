package com.cardprocess.produto.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product extends BaseAuditEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    protected Product() {
    }

    private Product(String name, ProductStatus status) {
        this.name = name;
        this.status = status;
    }

    public static Product create(String name) {
        return new Product(name, ProductStatus.ATIVO);
    }

    public void cancel() {
        this.status = ProductStatus.CANCELADO;
    }

    public boolean isActive() {
        return status == ProductStatus.ATIVO;
    }

    public String getName() {
        return name;
    }

    public ProductStatus getStatus() {
        return status;
    }
}
