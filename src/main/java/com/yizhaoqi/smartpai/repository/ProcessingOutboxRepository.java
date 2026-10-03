package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ProcessingOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProcessingOutboxRepository extends JpaRepository<ProcessingOutbox, Long> {
    Optional<ProcessingOutbox> findByEventId(String eventId);
    List<ProcessingOutbox> findByStatusOrderByIdAsc(ProcessingOutbox.Status status, Pageable page);

    @Modifying
    @Query("update ProcessingOutbox o set o.status = :sent, o.sentAt = :sentAt, o.lastError = null "
            + "where o.id = :id and o.status = :pending")
    int markSent(@Param("id") Long id, @Param("pending") ProcessingOutbox.Status pending,
                 @Param("sent") ProcessingOutbox.Status sent, @Param("sentAt") LocalDateTime sentAt);

    @Modifying
    @Query("update ProcessingOutbox o set o.retryCount = o.retryCount + 1, o.lastError = :error "
            + "where o.id = :id and o.status = :pending")
    int recordFailure(@Param("id") Long id, @Param("pending") ProcessingOutbox.Status pending,
                      @Param("error") String error);
}
