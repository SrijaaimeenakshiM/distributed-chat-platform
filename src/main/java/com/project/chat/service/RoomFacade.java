package com.project.chat.service;

import com.project.chat.dto.DTOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Public facade exposing RoomService methods for the REST controller.
 */
@Service("roomFacade")
@RequiredArgsConstructor
@Slf4j
public class RoomFacade {
    private final RoomService roomService;

    public DTOs.RoomResponse createRoom(DTOs.CreateRoomRequest req, Long creatorId) {
        return roomService.createRoom(req, creatorId);
    }

    public List<DTOs.RoomSummary> listPublicRooms() {
        return roomService.listPublicRooms();
    }

    public List<DTOs.RoomSummary> listMyRooms(Long userId) {
        return roomService.listMyRooms(userId);
    }

    public DTOs.RoomResponse getRoom(Long roomId, Long requesterId) {
        return roomService.getRoom(roomId, requesterId);
    }

    public void deleteRoom(Long roomId, Long requesterId) {
        roomService.deleteRoom(roomId, requesterId);
    }
}
