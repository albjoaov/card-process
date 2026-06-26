package com.cardprocess.portador.application;

import java.util.UUID;

public record CardView(UUID id, String maskedNumber, String status, ProductView product) {

    public record ProductView(UUID id, String name, String status) {
    }
}
