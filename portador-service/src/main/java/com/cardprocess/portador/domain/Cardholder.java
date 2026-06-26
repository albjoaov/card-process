package com.cardprocess.portador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "cardholder")
public class Cardholder extends BaseAuditEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 11, unique = true)
    private String cpf;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardholderStatus status;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    protected Cardholder() {
    }

    private Cardholder(String name, String cpf, LocalDate birthDate, UUID productId, CardholderStatus status) {
        this.name = name;
        this.cpf = cpf;
        this.birthDate = birthDate;
        this.productId = productId;
        this.status = status;
    }

    public static Cardholder register(String name, String cpf, LocalDate birthDate, UUID productId) {
        return new Cardholder(name, cpf, birthDate, productId, CardholderStatus.ATIVO);
    }

    public String getName() {
        return name;
    }

    public String getCpf() {
        return cpf;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public CardholderStatus getStatus() {
        return status;
    }

    public UUID getProductId() {
        return productId;
    }
}
