package com.project.chat.controller;

import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.User;
import com.project.chat.repository.UserRepository;
import com.project.chat.service.ChatService;
import com.project.chat.service.RoomFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j
class RoomController {

    private final RoomFacade roomFacade;
    private final ChatService chatService;
    private final UserRepository userRepository;

    /**
     * Create a new room. The authenticated user becomes the OWNER.
     */
    @PostMapping
    public ResponseEntity<DTOs.RoomResponse> createRoom(
            @Valid @RequestBody DTOs.CreateRoomRequest req,
            Authentication auth) {

        User user = resolveUser(auth);
        DTOs.RoomResponse response = roomFacade.createRoom(req, user.getId());
        log.info("[RoomController] Created room={} by={}", response.name(), user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all public rooms. No authentication required.
     */
    @GetMapping("/public")
    public ResponseEntity<List<DTOs.RoomSummary>> listPublic() {
        return ResponseEntity.ok(roomFacade.listPublicRooms());
    }

    /**
     * List rooms the authenticated user is a member of.
     */
    @GetMapping("/mine")
    public ResponseEntity<List<DTOs.RoomSummary>> listMyRooms(Authentication auth) {
        User user = resolveUser(auth);
        return ResponseEntity.ok(roomFacade.listMyRooms(user.getId()));
    }

    /**
     * Get room details by id. Private rooms require membership.
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<DTOs.RoomResponse> getRoom(
            @PathVariable Long roomId,
            Authentication auth) {

        User user = resolveUser(auth);
        return ResponseEntity.ok(roomFacade.getRoom(roomId, user.getId()));
    }

    /**
     * Join a room. Adds authenticated user as a MEMBER.
     */
    @PostMapping("/{roomId}/join")
    public ResponseEntity<DTOs.RoomResponse> joinRoom(
            @PathVariable Long roomId,
            Authentication auth) {

        User user = resolveUser(auth);
        DTOs.RoomResponse response = chatService.joinRoom(roomId, user.getId());
        return ResponseEntity.ok(response);
    }

    /**
     * Leave a room. Removes authenticated user from membership.
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable Long roomId,
            Authentication auth) {

        User user = resolveUser(auth);
        chatService.leaveRoom(roomId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Get the member list for a room. Requester must be a member.
     */
    @GetMapping("/{roomId}/members")
    public ResponseEntity<List<DTOs.RoomMemberResponse>> getMembers(
            @PathVariable Long roomId,
            Authentication auth) {

        User user = resolveUser(auth);
        return ResponseEntity.ok(chatService.getRoomMembers(roomId, user.getId()));
    }

    /**
     * Delete a room. Only the OWNER can perform this action.
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteRoom(
            @PathVariable Long roomId,
            Authentication auth) {

        User user = resolveUser(auth);
        roomFacade.deleteRoom(roomId, user.getId());
        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Authentication auth) {
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found: " + username));
    }
}
