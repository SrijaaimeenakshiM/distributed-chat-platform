package com.project.chat.service;

import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.User;
import com.project.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public DTOs.AuthResponse register(DTOs.RegisterRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new ChatExceptions.ConflictException("Username already taken: " + req.username());
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new ChatExceptions.ConflictException("Email already registered: " + req.email());
        }

        User user = User.builder()
                .username(req.username())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(req.displayName() != null ? req.displayName() : req.username())
                .isActive(true)
                .build();

        User saved = userRepository.save(user);
        log.info("[Auth] Registered new user: username={} email={}", saved.getUsername(), saved.getEmail());

        String token = jwtService.generateToken(saved.getUsername());
        return buildAuthResponse(token, saved);
    }

    public DTOs.AuthResponse login(DTOs.LoginRequest req) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            throw new ChatExceptions.UnauthorizedException("Invalid username or password");
        }

        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found"));

        String token = jwtService.generateToken(user.getUsername());
        log.info("[Auth] Login successful: username={}", user.getUsername());
        return buildAuthResponse(token, user);
    }

    private DTOs.AuthResponse buildAuthResponse(String token, User user) {
        DTOs.UserSummary summary = new DTOs.UserSummary(
                user.getId(), user.getUsername(),
                user.getDisplayName(), user.getAvatarUrl(), null);
        return new DTOs.AuthResponse(token, null, 86400L, summary);
    }
}
