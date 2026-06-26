package com.cardprocess.portador.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CpfValidator implements ConstraintValidator<Cpf, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 11 || digits.chars().distinct().count() == 1) {
            return false;
        }
        return hasValidCheckDigit(digits, 9, 10) && hasValidCheckDigit(digits, 10, 11);
    }

    private boolean hasValidCheckDigit(String digits, int position, int weightStart) {
        int sum = 0;
        for (int i = 0; i < position; i++) {
            sum += (digits.charAt(i) - '0') * (weightStart - i);
        }
        int remainder = sum % 11;
        int checkDigit = remainder < 2 ? 0 : 11 - remainder;
        return checkDigit == (digits.charAt(position) - '0');
    }
}
