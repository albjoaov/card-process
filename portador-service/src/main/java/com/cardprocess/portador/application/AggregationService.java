package com.cardprocess.portador.application;

import com.cardprocess.portador.domain.Cardholder;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AggregationService {

    private final CardholderService cardholderService;
    private final CardGateway cardGateway;

    public AggregationService(CardholderService cardholderService, CardGateway cardGateway) {
        this.cardholderService = cardholderService;
        this.cardGateway = cardGateway;
    }

    public CardholderAggregate aggregate(UUID cardholderId) {
        Cardholder cardholder = cardholderService.getById(cardholderId);
        CardView card = cardGateway.findByCardholder(cardholderId).orElse(null);
        return new CardholderAggregate(cardholder, card);
    }

    public record CardholderAggregate(Cardholder cardholder, CardView card) {
    }
}
