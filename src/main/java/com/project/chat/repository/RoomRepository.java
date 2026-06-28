package com.project.chat.repository;

import com.project.chat.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByIsPrivateFalseOrderByNameAsc();

    @Query("""
        SELECT r FROM Room r
        JOIN RoomMember rm ON rm.room.id = r.id
        WHERE rm.user.id = :userId
        ORDER BY r.updatedAt DESC
        """)
    List<Room> findRoomsByMemberId(@Param("userId") Long userId);

    Optional<Room> findByName(String name);

    boolean existsByName(String name);
}