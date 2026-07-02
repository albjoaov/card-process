package com.cardprocess.portador.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfValidatorTest {

    private final CpfValidator validator = new CpfValidator();

    @ParameterizedTest
    @ValueSource(strings = {"39053344705", "52998224725", "390.533.447-05"})
    void acceptsValidCpf(String cpf) {
        assertThat(validator.isValid(cpf, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "39053344704",
            "39053344715",
            "11111111111",
            "00000000000",
            "1234567890",
            "123456789012",
            "abcdefghijk",
            ""
    })
    void rejectsInvalidCpf(String cpf) {
        assertThat(validator.isValid(cpf, null)).isFalse();
    }

    @Test
    void rejectsNullCpf() {
        assertThat(validator.isValid(null, null)).isFalse();
    }
}
