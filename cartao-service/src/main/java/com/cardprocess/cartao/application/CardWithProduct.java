package com.cardprocess.cartao.application;

import com.cardprocess.cartao.domain.Card;

public record CardWithProduct(Card card, ProductSnapshot product) {
}
