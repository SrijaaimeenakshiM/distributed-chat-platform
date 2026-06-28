package com.project.chat.config;

import com.project.chat.service.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Inject by the Spring interface type.
    // The concrete bean is JwtHandshakeInterceptorBean in JwtFilters.java,
    // registered as @Component("jwtHandshakeInterceptor").
    // @Qualifier pins Spring to that exact bean if multiple HandshakeInterceptors exist.
    private final HandshakeInterceptor jwtHandshakeInterceptor;
    private final JwtService jwtService;

    public WebSocketConfig(
            @Qualifier("jwtHandshakeInterceptor") HandshakeInterceptor jwtHandshakeInterceptor,
            JwtService jwtService) {
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.jwtService = jwtService;
    }

    @Value("${app.websocket.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.websocket.heartbeat-interval}")
    private long heartbeatInterval;

    @Value("${app.websocket.heartbeat-timeout}")
    private long heartbeatTimeout;

    /**
     * Configure STOMP message broker.
     *  /topic  → messages broadcast to all subscribers in a room
     *  /queue  → point-to-point messages (user-specific notifications)
     *  /app    → messages routed to @MessageMapping controller methods
     *  /user   → prefix for user-specific destinations (presence, errors)
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker for topic and queue destinations.
        // Redis pub/sub handles cross-server fan-out; the local broker
        // delivers messages to sessions on THIS server instance only.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{heartbeatInterval, heartbeatTimeout})
                .setTaskScheduler(new org.springframework.scheduling.concurrent
                        .ThreadPoolTaskScheduler() {{ initialize(); }});

        // Prefix for client → server messages routed to @MessageMapping
        registry.setApplicationDestinationPrefixes("/app");

        // Prefix enabling /user/{username}/queue/... destinations
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Register the WebSocket endpoint.
     * SockJS provides fallback transport (XHR-streaming, long-polling)
     * for clients that cannot establish a native WebSocket connection.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS()
                .setHeartbeatTime(heartbeatInterval)
                .setDisconnectDelay(5000)
                .setClientLibraryUrl("https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js");
    }

    /**
     * Increase buffer sizes to handle large message volumes under load.
     * 512KB send buffer; 1MB receive buffer; 30s send timeout.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(512 * 1024)          // 512 KB per message
                .setSendBufferSizeLimit(1024 * 1024)       // 1 MB send buffer
                .setSendTimeLimit(30 * 1000)               // 30s before disconnect
                .setTimeToFirstMessage(60 * 1000);         // 60s handshake window
    }

    /**
     * STOMP channel interceptor: validate JWT on CONNECT frames.
     * This runs before the message reaches any controller, ensuring
     * unauthenticated WebSocket connections are rejected immediately.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) return message;

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            String username = jwtService.extractUsername(token);
                            if (username != null && jwtService.isTokenValid(token, username)) {
                                var auth = new UsernamePasswordAuthenticationToken(
                                        username, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
                                accessor.setUser(auth);
                                SecurityContextHolder.getContext().setAuthentication(auth);
                                log.debug("[WS-CONNECT] Authenticated user: {}", username);
                            }
                        } catch (Exception e) {
                            log.warn("[WS-CONNECT] JWT validation failed: {}", e.getMessage());
                            throw new org.springframework.security.access.AccessDeniedException(
                                    "Invalid or expired JWT token");
                        }
                    } else {
                        log.warn("[WS-CONNECT] Missing Authorization header");
                        throw new org.springframework.security.access.AccessDeniedException(
                                "Authorization header required");
                    }
                }
                return message;
            }
        });
    }
}