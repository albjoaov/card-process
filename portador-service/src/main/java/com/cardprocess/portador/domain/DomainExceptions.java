package com.cardprocess.portador.domain;

import java.util.UUID;

public final class DomainExceptions {

    private DomainExceptions() {
    }

    public static class CardholderNotFoundException extends RuntimeException {
        public CardholderNotFoundException(UUID id) {
            super("Cardholder not found: " + id);
        }
    }

    public static class DuplicateCpfException extends RuntimeException {
        public DuplicateCpfException(String cpf) {
            super("Cardholder already registered for CPF: " + maskCpf(cpf));
        }

        private static String maskCpf(String cpf) {
            if (cpf == null || cpf.length() < 3) {
                return "***";
            }
            return "*********" + cpf.substring(cpf.length() - 2);
        }
    }

    public static class UsernameAlreadyExistsException extends RuntimeException {
        public UsernameAlreadyExistsException(String username) {
            super("Username already exists: " + username);
        }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid username or password");
        }
    }

    public static class CardServiceUnavailableException extends RuntimeException {
        public CardServiceUnavailableException(Throwable cause) {
            super("Cartao Service is unavailable", cause);
        }
    }
}
