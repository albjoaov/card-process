package com.cardprocess.portador.application;

import com.cardprocess.portador.domain.AppUser;
import com.cardprocess.portador.domain.DomainExceptions.InvalidCredentialsException;
import com.cardprocess.portador.domain.DomainExceptions.UsernameAlreadyExistsException;
import com.cardprocess.portador.infrastructure.persistence.AppUserRepository;
import com.cardprocess.portador.infrastructure.security.JwtService;
import com.cardprocess.portador.infrastructure.security.JwtService.IssuedToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public void register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException(username);
        }
        userRepository.save(AppUser.create(username, passwordEncoder.encode(rawPassword)));
    }

    @Transactional(readOnly = true)
    public IssuedToken login(String username, String rawPassword) {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return jwtService.issue(username);
    }
}
