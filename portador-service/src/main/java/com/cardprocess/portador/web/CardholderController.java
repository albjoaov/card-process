package com.cardprocess.portador.web;

import com.cardprocess.portador.application.AggregationService;
import com.cardprocess.portador.application.CardholderService;
import com.cardprocess.portador.web.dto.CardholderAggregateResponse;
import com.cardprocess.portador.web.dto.CardholderResponse;
import com.cardprocess.portador.web.dto.CreateCardholderRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cardholders")
@SecurityRequirement(name = "bearerAuth")
public class CardholderController {

    private final CardholderService cardholderService;
    private final AggregationService aggregationService;

    public CardholderController(CardholderService cardholderService, AggregationService aggregationService) {
        this.cardholderService = cardholderService;
        this.aggregationService = aggregationService;
    }

    @PostMapping
    public ResponseEntity<CardholderResponse> create(@Valid @RequestBody CreateCardholderRequest request) {
        CardholderResponse response = CardholderResponse.from(cardholderService.register(
                request.name(), request.cpf(), request.birthDate(), request.productId()));
        return ResponseEntity.created(URI.create("/cardholders/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public CardholderResponse getById(@PathVariable UUID id) {
        return CardholderResponse.from(cardholderService.getById(id));
    }

    @GetMapping("/{id}/aggregate")
    public CardholderAggregateResponse aggregate(@PathVariable UUID id) {
        return CardholderAggregateResponse.from(aggregationService.aggregate(id));
    }
}
