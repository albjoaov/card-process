package com.cardprocess.portador.web;

import com.cardprocess.portador.domain.DomainExceptions.CardServiceUnavailableException;
import com.cardprocess.portador.domain.DomainExceptions.CardholderNotFoundException;
import com.cardprocess.portador.domain.DomainExceptions.DuplicateCpfException;
import com.cardprocess.portador.domain.DomainExceptions.InvalidCredentialsException;
import com.cardprocess.portador.domain.DomainExceptions.UsernameAlreadyExistsException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CardholderNotFoundException.class)
    public ProblemDetail handleNotFound(CardholderNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Cardholder not found", exception.getMessage());
    }

    @ExceptionHandler({DuplicateCpfException.class, UsernameAlreadyExistsException.class})
    public ProblemDetail handleConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Resource conflict", exception.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", exception.getMessage());
    }

    @ExceptionHandler(CardServiceUnavailableException.class)
    public ProblemDetail handleDownstreamUnavailable(CardServiceUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Downstream unavailable", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Validation failed");
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
