package com.cardprocess.cartao.web;

import com.cardprocess.cartao.application.CardQueryService;
import com.cardprocess.cartao.web.dto.CardResponse;
import com.cardprocess.cartao.web.dto.UpdateCardStatusRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cards")
public class CardController {

    private final CardQueryService cardQueryService;

    public CardController(CardQueryService cardQueryService) {
        this.cardQueryService = cardQueryService;
    }

    @GetMapping("/{id}")
    public CardResponse getById(@PathVariable UUID id) {
        return CardResponse.from(cardQueryService.getById(id));
    }

    @GetMapping("/by-cardholder/{cardholderId}")
    public CardResponse getByCardholder(@PathVariable UUID cardholderId) {
        return CardResponse.from(cardQueryService.getByCardholder(cardholderId));
    }

    @PatchMapping("/{id}/status")
    public CardResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateCardStatusRequest request) {
        return CardResponse.from(cardQueryService.updateStatus(id, request.status()));
    }
}
