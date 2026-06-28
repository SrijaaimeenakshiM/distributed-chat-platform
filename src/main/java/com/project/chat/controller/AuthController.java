package com.project.chat.controller;

import com.project.chat.dto.DTOs;
import com.project.chat.service.AuthFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
class AuthController {

    private final AuthFacade authFacade;

    /**
     * Register a new user account and receive a JWT.
     */
    @PostMapping("/register")
    public ResponseEntity<DTOs.AuthResponse> register(@Valid @RequestBody DTOs.RegisterRequest req) {
        log.info("[AuthController] Register: username={}", req.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(authFacade.register(req));
    }

    /**
     * Authenticate with username + password and receive a JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<DTOs.AuthResponse> login(@Valid @RequestBody DTOs.LoginRequest req) {
        log.info("[AuthController] Login: username={}", req.username());
        return ResponseEntity.ok(authFacade.login(req));
    }

    /**
     * Validate the current token — returns 200 if valid, 401 if not (handled by filter).
     */
    @GetMapping("/validate")
    public ResponseEntity<Void> validate() {
        return ResponseEntity.ok().build();
    }
}
