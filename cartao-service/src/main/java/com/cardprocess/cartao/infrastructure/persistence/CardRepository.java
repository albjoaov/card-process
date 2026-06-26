package com.cardprocess.cartao.infrastructure.persistence;

import com.cardprocess.cartao.domain.Card;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, UUID> {

    boolean existsByCorrelationId(UUID correlationId);

    boolean existsByCardholderId(UUID cardholderId);

    Optional<Card> findByCardholderId(UUID cardholderId);
}
