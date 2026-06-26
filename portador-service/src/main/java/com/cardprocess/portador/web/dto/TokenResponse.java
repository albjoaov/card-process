package com.cardprocess.portador.web.dto;

public record TokenResponse(String token, long expiresIn) {
}
