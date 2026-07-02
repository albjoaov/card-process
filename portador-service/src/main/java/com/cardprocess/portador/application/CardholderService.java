package com.cardprocess.portador.application;

import com.cardprocess.portador.domain.Cardholder;
import com.cardprocess.portador.domain.DomainExceptions.CardholderNotFoundException;
import com.cardprocess.portador.domain.DomainExceptions.DuplicateCpfException;
import com.cardprocess.portador.infrastructure.persistence.CardholderRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardholderService {

    private static final Logger log = LoggerFactory.getLogger(CardholderService.class);

    private final CardholderRepository repository;
    private final IssuancePublisher issuancePublisher;

    public CardholderService(CardholderRepository repository, IssuancePublisher issuancePublisher) {
        this.repository = repository;
        this.issuancePublisher = issuancePublisher;
    }

    @Transactional
    public Cardholder register(String name, String cpf, LocalDate birthDate, UUID productId) {
        String normalizedCpf = cpf.replaceAll("\\D", "");
        if (repository.existsByCpf(normalizedCpf)) {
            throw new DuplicateCpfException(normalizedCpf);
        }
        Cardholder cardholder = repository.saveAndFlush(
                Cardholder.register(name, normalizedCpf, birthDate, productId));
        UUID correlationId = UUID.randomUUID();
        issuancePublisher.publish(new IssuanceMessage(cardholder.getId(), productId, correlationId));
        log.info("Issuance triggered cardholderId={} productId={} correlationId={}",
                cardholder.getId(), productId, correlationId);
        return cardholder;
    }

    @Transactional(readOnly = true)
    public Cardholder getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new CardholderNotFoundException(id));
    }
}
