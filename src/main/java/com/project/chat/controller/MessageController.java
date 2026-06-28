package com.project.chat.controller;


import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.User;
import com.project.chat.repository.UserRepository;
import com.project.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
@Slf4j
class MessageController {

    private final ChatService chatService;
    private final UserRepository userRepository;

    /**
     * Fetch paginated message history using keyset pagination.
     *
     * GET /api/messages/{roomId}/history
     * Query params:
     *   beforeId  (optional) — fetch messages older than this message id
     *
     * First page:  GET /api/messages/42/history
     * Next page:   GET /api/messages/42/history?beforeId=<nextCursor from previous response>
     */
    @GetMapping("/{roomId}/history")
    public ResponseEntity<DTOs.MessagePageResponse> getHistory(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long beforeId,
            Authentication auth) {

        User user = resolveUser(auth);
        DTOs.MessagePageResponse page = chatService.getHistory(roomId, beforeId, user.getId());
        return ResponseEntity.ok(page);
    }

    private User resolveUser(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found: " + auth.getName()));
    }
}

