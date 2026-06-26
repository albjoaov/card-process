package com.cardprocess.cartao.web;

import com.cardprocess.cartao.domain.DomainExceptions.CardNotFoundException;
import com.cardprocess.cartao.domain.DomainExceptions.ProductServiceUnavailableException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CardNotFoundException.class)
    public ProblemDetail handleNotFound(CardNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Card not found", exception.getMessage());
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ProblemDetail handleUnavailable(ProductServiceUnavailableException exception) {
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
