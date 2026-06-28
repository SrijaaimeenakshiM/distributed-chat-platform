package com.project.chat.service;

import com.project.chat.dto.DTOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Public facade exposing AuthService methods for the REST controller.
 * Wrapping avoids leaking package-private service classes into controller layer.
 */
@Service("authFacade")
@RequiredArgsConstructor
@Slf4j
public class AuthFacade {
    public final AuthService authService;

    public DTOs.AuthResponse register(DTOs.RegisterRequest req) {
        return authService.register(req);
    }

    public DTOs.AuthResponse login(DTOs.LoginRequest req) {
        return authService.login(req);
    }
}
