package com.cardprocess.portador.web;

import com.cardprocess.portador.application.AuthService;
import com.cardprocess.portador.infrastructure.security.JwtService.IssuedToken;
import com.cardprocess.portador.web.dto.AuthRequest;
import com.cardprocess.portador.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody AuthRequest request) {
        authService.register(request.username(), request.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody AuthRequest request) {
        IssuedToken issued = authService.login(request.username(), request.password());
        return new TokenResponse(issued.token(), issued.expiresInSeconds());
    }
}
