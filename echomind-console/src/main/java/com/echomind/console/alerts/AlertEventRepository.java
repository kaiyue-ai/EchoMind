package com.echomind.console.alerts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, String> {

    List<AlertEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtGreaterThanEqual(Instant createdAt);

    boolean existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType, AlertStatus status, Instant since);

    long countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType, AlertStatus status, Instant since);

    boolean existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(AlertType alertType, Instant since);
}
