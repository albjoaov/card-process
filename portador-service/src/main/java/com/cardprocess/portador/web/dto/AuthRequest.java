package com.cardprocess.portador.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank String username,
        @NotBlank @Size(min = 6, max = 100) String password) {
}
