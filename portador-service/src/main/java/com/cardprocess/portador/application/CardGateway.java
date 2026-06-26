package com.cardprocess.portador.application;

import java.util.Optional;
import java.util.UUID;

public interface CardGateway {

    Optional<CardView> findByCardholder(UUID cardholderId);
}
