package com.echomind.console.sensitive;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SensitiveEventRepository extends JpaRepository<SensitiveEventEntity, String> {

    List<SensitiveEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtGreaterThanEqual(Instant createdAt);
}
