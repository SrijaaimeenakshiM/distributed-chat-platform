package com.project.chat.security;

import com.project.chat.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
// ─── JwtHandshakeInterceptor ─────────────────────────────────────────────────

/**
 * Validates JWT during the WebSocket HTTP upgrade handshake.
 *
 * Browsers cannot set custom headers on WebSocket connections, so the token
 * is passed as a query parameter: ws://host/ws?token=<jwt>
 *
 * On success, stores userId and username in the session attributes map so
 * ChatController can retrieve them without a DB round-trip on every message.
 *
 * Registered as bean name "jwtHandshakeInterceptor" so WebSocketConfig can
 * inject it via @Qualifier("jwtHandshakeInterceptor") as a HandshakeInterceptor.
 */
@Component("jwtHandshakeInterceptor")
@RequiredArgsConstructor
@Slf4j
class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {

        String token = null;

        // Check query param first (SockJS sends token here)
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        // Fallback to Authorization header
        if (token == null) {
            String header = request.getHeaders().getFirst("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7);
            }
        }

        if (token == null) {
            log.warn("[WS-Handshake] No token found");
            return false;
        }

        // validate token and set attributes...
        attributes.put("token", token);
        return true;
    }
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("[WS-Handshake] Post-handshake error: {}", exception.getMessage());
        }
    }
}