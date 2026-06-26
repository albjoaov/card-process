package com.cardprocess.portador.web.dto;

import com.cardprocess.portador.web.validation.Cpf;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record CreateCardholderRequest(
        @NotBlank @Size(max = 120) String name,
        @Cpf String cpf,
        @NotNull @Past LocalDate birthDate,
        @NotNull UUID productId) {
}
