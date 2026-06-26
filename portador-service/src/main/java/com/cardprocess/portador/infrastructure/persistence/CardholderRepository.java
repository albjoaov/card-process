package com.cardprocess.portador.infrastructure.persistence;

import com.cardprocess.portador.domain.Cardholder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardholderRepository extends JpaRepository<Cardholder, UUID> {

    boolean existsByCpf(String cpf);
}
