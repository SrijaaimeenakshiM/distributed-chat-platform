package com.project.chat.repository;

import com.project.chat.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
        SELECT m FROM Message m
        WHERE m.roomId = :roomId
          AND m.id < :beforeId
          AND m.deleted = false
        ORDER BY m.id DESC
        """)
    List<Message> findMessagesBefore(
            @Param("roomId") Long roomId,
            @Param("beforeId") Long beforeId,
            Pageable pageable
    );

    @Query("""
        SELECT m FROM Message m
        WHERE m.roomId = :roomId
          AND m.deleted = false
        ORDER BY m.id DESC
        """)
    List<Message> findRecentMessages(
            @Param("roomId") Long roomId,
            Pageable pageable
    );

    @Query("""
        SELECT COUNT(m)
        FROM Message m
        WHERE m.roomId = :roomId
          AND m.id < :beforeId
          AND m.deleted = false
        """)
    long countMessagesBefore(
            @Param("roomId") Long roomId,
            @Param("beforeId") Long beforeId
    );

    @Modifying
    @Query("""
        UPDATE Message m
        SET m.deleted = true
        WHERE m.id = :id
          AND m.senderId = :senderId
        """)
    int softDeleteMessage(
            @Param("id") Long id,
            @Param("senderId") Long senderId
    );

    @Modifying
    @Query("""
        UPDATE Message m
        SET m.content = :content,
            m.editedAt = :editedAt
        WHERE m.id = :id
          AND m.senderId = :senderId
        """)
    int editMessage(
            @Param("id") Long id,
            @Param("senderId") Long senderId,
            @Param("content") String content,
            @Param("editedAt") Instant editedAt
    );
}