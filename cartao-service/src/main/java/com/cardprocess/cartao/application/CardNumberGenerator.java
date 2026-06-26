package com.cardprocess.cartao.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class CardNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generateMasked() {
        return String.format("**** **** **** %04d", random.nextInt(10000));
    }
}
