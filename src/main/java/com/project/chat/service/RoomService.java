package com.project.chat.service;

import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.MemberRole;
import com.project.chat.model.Room;
import com.project.chat.model.RoomMember;
import com.project.chat.model.User;
import com.project.chat.repository.RoomMemberRepository;
import com.project.chat.repository.RoomRepository;
import com.project.chat.repository.UserRepository;
import com.project.chat.websocket.SessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final SessionRegistry sessionRegistry;
    private final ChatService chatService;

    @Transactional
    public DTOs.RoomResponse createRoom(DTOs.CreateRoomRequest req, Long creatorId) {
        if (roomRepository.existsByName(req.name())) {
            throw new ChatExceptions.ConflictException("Room name already exists: " + req.name());
        }

        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("User not found"));

        Room room = Room.builder()
                .name(req.name())
                .description(req.description())
                .isPrivate(req.isPrivate())
                .createdBy(creatorId)
                .maxMembers(req.maxMembers())
                .build();

        Room saved = roomRepository.save(room);

        // Creator is automatically added as OWNER
        RoomMember ownerMembership = RoomMember.builder()
                .room(saved)
                .user(creator)
                .role(MemberRole.OWNER)
                .build();
        roomMemberRepository.save(ownerMembership);

        sessionRegistry.addToRoom(saved.getId(), creator.getUsername());

        log.info("[RoomService] Room created: id={} name={} by={}", saved.getId(), saved.getName(), creator.getUsername());
        return chatService.toRoomResponse(saved, creatorId);
    }

    @Transactional(readOnly = true)
    public List<DTOs.RoomSummary> listPublicRooms() {
        return roomRepository.findByIsPrivateFalseOrderByNameAsc().stream()
                .map(r -> new DTOs.RoomSummary(
                        r.getId(), r.getName(), r.isPrivate(),
                        roomMemberRepository.countByRoomId(r.getId()),
                        r.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DTOs.RoomSummary> listMyRooms(Long userId) {
        return roomRepository.findRoomsByMemberId(userId).stream()
                .map(r -> new DTOs.RoomSummary(
                        r.getId(), r.getName(), r.isPrivate(),
                        roomMemberRepository.countByRoomId(r.getId()),
                        r.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DTOs.RoomResponse getRoom(Long roomId, Long requesterId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ChatExceptions.NotFoundException("Room not found: " + roomId));

        if (room.isPrivate() && !roomMemberRepository.existsByRoomIdAndUserId(roomId, requesterId)) {
            throw new ChatExceptions.ForbiddenException("Private room — membership required");
        }

        return chatService.toRoomResponse(room, requesterId);
    }

    @Transactional
    public void deleteRoom(Long roomId, Long requesterId) {
        RoomMember membership = roomMemberRepository.findByRoomIdAndUserId(roomId, requesterId)
                .orElseThrow(() -> new ChatExceptions.ForbiddenException("Not a member of this room"));

        if (membership.getRole() != MemberRole.OWNER) {
            throw new ChatExceptions.ForbiddenException("Only the room OWNER can delete a room");
        }

        roomRepository.deleteById(roomId);
        log.info("[RoomService] Room {} deleted by userId={}", roomId, requesterId);
    }
}
