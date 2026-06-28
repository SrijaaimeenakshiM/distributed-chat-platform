package com.project.chat.exception;

import com.project.chat.dto.DTOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * ChatExceptions — all domain exception types + global @RestControllerAdvice handler.
 *
 * Every exception maps to an HTTP status code and is serialised as
 * DTOs.ErrorResponse JSON so the React client gets a consistent error shape.
 *
 * These same exception types are thrown from @MessageMapping STOMP handlers;
 * ChatController's @MessageExceptionHandler catches them there and routes the
 * error to /user/queue/errors instead of returning an HTTP response.
 */
public final class ChatExceptions {

    // ─── 400 Bad Request ────────────────────────────────────────────────────

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    // ─── 401 Unauthorized ────────────────────────────────────────────────────

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    // ─── 403 Forbidden ───────────────────────────────────────────────────────

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    // ─── 404 Not Found ───────────────────────────────────────────────────────

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    // ─── 409 Conflict ────────────────────────────────────────────────────────

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    // ─── 429 Too Many Requests ───────────────────────────────────────────────

    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    // ─── 503 Service Unavailable ─────────────────────────────────────────────

    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) {
            super(message);
        }
    }

    // Private constructor — this is a namespace class, not instantiable
    private ChatExceptions() {}

    // ═══════════════════════════════════════════════════════════════════════
    // Global REST exception handler
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * @RestControllerAdvice intercepts exceptions thrown from any @RestController
     * and converts them to structured JSON error responses.
     *
     * STOMP WebSocket errors are NOT handled here — they go through
     * ChatController's @MessageExceptionHandler instead.
     */
    @RestControllerAdvice
    @Slf4j
    static class GlobalExceptionHandler {

        @ExceptionHandler(BadRequestException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleBadRequest(
                BadRequestException ex, WebRequest request) {
            return build(HttpStatus.BAD_REQUEST, ex, request);
        }

        @ExceptionHandler(UnauthorizedException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleUnauthorized(
                UnauthorizedException ex, WebRequest request) {
            return build(HttpStatus.UNAUTHORIZED, ex, request);
        }

        @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
        public ResponseEntity<DTOs.ErrorResponse> handleForbidden(
                RuntimeException ex, WebRequest request) {
            return build(HttpStatus.FORBIDDEN, ex, request);
        }

        @ExceptionHandler(NotFoundException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleNotFound(
                NotFoundException ex, WebRequest request) {
            return build(HttpStatus.NOT_FOUND, ex, request);
        }

        @ExceptionHandler(ConflictException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleConflict(
                ConflictException ex, WebRequest request) {
            return build(HttpStatus.CONFLICT, ex, request);
        }

        @ExceptionHandler(RateLimitException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleRateLimit(
                RateLimitException ex, WebRequest request) {
            return build(HttpStatus.TOO_MANY_REQUESTS, ex, request);
        }

        @ExceptionHandler(ServiceUnavailableException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleServiceUnavailable(
                ServiceUnavailableException ex, WebRequest request) {
            return build(HttpStatus.SERVICE_UNAVAILABLE, ex, request);
        }

        @ExceptionHandler(BadCredentialsException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleBadCredentials(
                BadCredentialsException ex, WebRequest request) {
            return build(HttpStatus.UNAUTHORIZED, "Invalid username or password", request);
        }

        /**
         * Handle @Valid / @Validated bean validation failures.
         * Collects all field errors into a single readable message.
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<DTOs.ErrorResponse> handleValidation(
                MethodArgumentNotValidException ex, WebRequest request) {
            String message = ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("; "));
            return build(HttpStatus.BAD_REQUEST, message, request);
        }

        /**
         * Catch-all for unexpected exceptions.
         * Logs the full stack trace server-side but returns a generic message
         * to the client to avoid leaking internal details.
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<DTOs.ErrorResponse> handleGeneric(
                Exception ex, WebRequest request) {
            log.error("[GlobalExceptionHandler] Unhandled exception: {}", ex.getMessage(), ex);
            return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
        }

        // ─── Builders ────────────────────────────────────────────────────

        private ResponseEntity<DTOs.ErrorResponse> build(
                HttpStatus status, RuntimeException ex, WebRequest request) {
            return build(status, ex.getMessage(), request);
        }

        private ResponseEntity<DTOs.ErrorResponse> build(
                HttpStatus status, String message, WebRequest request) {
            DTOs.ErrorResponse body = new DTOs.ErrorResponse(
                    status.getReasonPhrase(),
                    message,
                    status.value(),
                    Instant.now(),
                    request.getDescription(false).replace("uri=", "")
            );
            log.debug("[GlobalExceptionHandler] {} — {}", status.value(), message);
            return ResponseEntity.status(status).body(body);
        }
    }
}
