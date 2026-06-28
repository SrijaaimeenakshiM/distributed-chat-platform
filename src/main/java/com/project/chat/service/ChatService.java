package com.project.chat.service;

import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.Message;
import com.project.chat.model.MemberRole;
import com.project.chat.model.MessageType;
import org.springframework.data.domain.PageRequest;
import com.project.chat.model.Room;
import com.project.chat.model.RoomMember;
import com.project.chat.model.User;
import com.project.chat.repository.MessageRepository;
import com.project.chat.repository.RoomMemberRepository;
import com.project.chat.repository.RoomRepository;
import com.project.chat.repository.UserRepository;
import com.project.chat.websocket.SessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final MessageProcessingService messageProcessingService;
    private final PresenceService presenceService;
    private final RateLimitService rateLimitService;
    private final SessionRegistry sessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.server-id}")
    private String serverId;

    private static final int PAGE_SIZE = 50;

    // ─── Message Sending ─────────────────────────────────────────────────────

    /**
     * Entry point for inbound STOMP messages.
     * Validates membership and rate limit, then enqueues for async processing.
     */
    @Transactional(readOnly = true)
    public boolean processIncomingMessage(DTOs.SendMessageRequest request,
                                          Long senderId, String senderUsername) {
        // 1. Verify sender is a member of the room
        if (!roomMemberRepository.existsByRoomIdAndUserId(request.roomId(), senderId)) {
            log.warn("[ChatService] Non-member {} attempted to post to room {}", senderUsername, request.roomId());
            throw new ChatExceptions.ForbiddenException("You are not a member of this room");
        }

        // 2. Redis sliding-window rate limit check
        if (!rateLimitService.isAllowed(senderUsername)) {
            log.warn("[ChatService] Rate limit exceeded for user={}", senderUsername);
            throw new ChatExceptions.RateLimitException("Message rate limit exceeded. Slow down.");
        }

        // 3. Enqueue for async persist + publish (non-blocking)
        boolean enqueued = messageProcessingService.enqueue(request, senderId, senderUsername);

        // Update presence heartbeat asynchronously
        updatePresenceAsync(senderId, senderUsername, request.roomId());

        return enqueued;
    }

    @Async("presenceExecutor")
    public void updatePresenceAsync(Long userId, String username, Long roomId) {
        presenceService.heartbeat(userId, username);
    }

    // ─── History (Keyset Pagination) ─────────────────────────────────────────

    /**
     * Fetch message history using keyset pagination.
     *
     * - If beforeId is null, fetches the most recent PAGE_SIZE messages.
     * - Otherwise, fetches messages with id < beforeId (older messages).
     * - Returns a cursor pointing to the oldest message in this page.
     *   Pass it as beforeId in the next call to page backwards in time.
     */
    @Transactional(readOnly = true)
    public DTOs.MessagePageResponse getHistory(Long roomId, Long beforeId, Long requesterId) {
        // Verify requester is a room member
        if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, requesterId)) {
            throw new ChatExceptions.ForbiddenException("Not a member of this room");
        }

        List<Message> messages;
        if (beforeId == null) {
            messages = messageRepository.findRecentMessages(
                    roomId,
                    PageRequest.of(0, PAGE_SIZE + 1)
            );
        } else {
            messages = messageRepository.findMessagesBefore(
                    roomId,
                    beforeId,
                    PageRequest.of(0, PAGE_SIZE + 1)
            );
        }

        // Fetch one extra record to determine if there are more pages
        boolean hasMore = messages.size() > PAGE_SIZE;
        if (hasMore) {
            messages = messages.subList(0, PAGE_SIZE);
        }

        List<DTOs.MessageResponse> responses = messages.stream()
                .map(messageProcessingService::toMessageResponse)
                .collect(Collectors.toList());

        // The cursor is the id of the oldest message in this page (last in DESC order)
        Long nextCursor = responses.isEmpty() ? null :
                responses.get(responses.size() - 1).id();

        return new DTOs.MessagePageResponse(responses, nextCursor, hasMore);
    }

    // ─── Typing Indicators ───────────────────────────────────────────────────

    /**
     * Broadcast a typing indicator to all room subscribers.
     * Typing events are ephemeral — NOT persisted to the database.
     * Published directly to the STOMP topic, bypassing the queue.
     */
    public void broadcastTyping(Long roomId, Long userId, String username, boolean isTyping) {
        DTOs.TypingEvent event = new DTOs.TypingEvent(roomId, userId, username, isTyping);
        DTOs.ChatEvent chatEvent = new DTOs.ChatEvent("TYPING", roomId, event, Instant.now(), serverId);
        messagingTemplate.convertAndSend("/topic/room." + roomId, chatEvent);
    }

    // ─── Room Membership ─────────────────────────────────────────────────────

    /**
     * Join a room: adds the user as a MEMBER and broadcasts a JOIN system message.
     */
    @Transactional
    public DTOs.RoomResponse joinRoom(Long roomId, Long userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("Room not found: " + roomId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found: " + userId));

        if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            // Already a member — idempotent
            return toRoomResponse(room, userId);
        }

        int currentCount = roomMemberRepository.countByRoomId(roomId);
        if (currentCount >= room.getMaxMembers()) {
            throw new ChatExceptions.BadRequestException("Room is full (" + room.getMaxMembers() + " members max)");
        }

        RoomMember member = RoomMember.builder()
                .room(room)
                .user(user)
                .role(MemberRole.MEMBER)
                .build();
        roomMemberRepository.save(member);

        // Update local session registry for membership checks
        sessionRegistry.addToRoom(roomId, user.getUsername());

        // Broadcast JOIN system message
        broadcastSystemMessage(roomId, user.getUsername() + " joined the room", MessageType.JOIN);

        log.info("[ChatService] User {} joined room {}", user.getUsername(), room.getName());
        return toRoomResponse(room, userId);
    }

    /**
     * Leave a room: removes membership and broadcasts a LEAVE system message.
     */
    @Transactional
    public void leaveRoom(Long roomId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found: " + userId));

        if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            throw new ChatExceptions.BadRequestException("Not a member of this room");
        }

        roomMemberRepository.deleteByRoomIdAndUserId(roomId, userId);
        sessionRegistry.removeFromRoom(roomId, user.getUsername());

        broadcastSystemMessage(roomId, user.getUsername() + " left the room", MessageType.LEAVE);
        log.info("[ChatService] User {} left room {}", user.getUsername(), roomId);
    }

    @Async("redisPublishExecutor")
    public void broadcastSystemMessage(Long roomId, String content, MessageType type) {
        DTOs.MessageResponse sysMsg = new DTOs.MessageResponse(
                null, roomId, 0L, "system", "System",
                content, type, Instant.now(), null, false, 0L);
        DTOs.ChatEvent event = new DTOs.ChatEvent(type.name(), roomId, sysMsg, Instant.now(), serverId);
        messagingTemplate.convertAndSend("/topic/room." + roomId, event);
    }

    // ─── Room Members List ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DTOs.RoomMemberResponse> getRoomMembers(Long roomId, Long requesterId) {
        if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, requesterId)) {
            throw new ChatExceptions.ForbiddenException("Not a member of this room");
        }
        return roomMemberRepository.findMembersWithUserByRoomId(roomId).stream()
                .map(rm -> new DTOs.RoomMemberResponse(
                        rm.getUser().getId(),
                        rm.getUser().getUsername(),
                        rm.getUser().getDisplayName(),
                        rm.getUser().getAvatarUrl(),
                        rm.getRole(),
                        rm.getJoinedAt(),
                        presenceService.getUserStatus(rm.getUser().getId())
                ))
                .collect(Collectors.toList());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DTOs.RoomResponse toRoomResponse(Room room, Long currentUserId) {
        int memberCount = roomMemberRepository.countByRoomId(room.getId());
        MemberRole role = roomMemberRepository
                .findByRoomIdAndUserId(room.getId(), currentUserId)
                .map(RoomMember::getRole)
                .orElse(null);
        User creator = userRepository.findById(room.getCreatedBy()).orElse(null);
        DTOs.UserSummary creatorSummary = creator == null ? null :
                new DTOs.UserSummary(creator.getId(), creator.getUsername(),
                        creator.getDisplayName(), creator.getAvatarUrl(), null);

        return new DTOs.RoomResponse(
                room.getId(), room.getName(), room.getDescription(),
                room.isPrivate(), room.getMaxMembers(), memberCount,
                room.getCreatedAt(), creatorSummary, role);
    }
}
