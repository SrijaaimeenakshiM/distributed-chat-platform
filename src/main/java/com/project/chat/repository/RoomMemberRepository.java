package com.project.chat.repository;

import com.project.chat.model.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    Optional<RoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

    boolean existsByRoomIdAndUserId(Long roomId, Long userId);

    List<RoomMember> findByRoomId(Long roomId);

    List<RoomMember> findByUserId(Long userId);

    int countByRoomId(Long roomId);

    void deleteByRoomIdAndUserId(Long roomId, Long userId);

    @Query("""
        SELECT rm
        FROM RoomMember rm
        JOIN FETCH rm.user
        WHERE rm.room.id = :roomId
        ORDER BY rm.joinedAt ASC
        """)
    List<RoomMember> findMembersWithUserByRoomId(
            @Param("roomId") Long roomId
    );

    @Modifying
    @Query("""
        UPDATE RoomMember rm
        SET rm.lastReadMessageId = :messageId
        WHERE rm.room.id = :roomId
          AND rm.user.id = :userId
        """)
    int updateLastRead(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId,
            @Param("messageId") Long messageId
    );
}