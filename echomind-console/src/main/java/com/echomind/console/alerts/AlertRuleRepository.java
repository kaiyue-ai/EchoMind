package com.echomind.console.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, String> {

    Optional<AlertRuleEntity> findFirstByAlertType(AlertType alertType);

    List<AlertRuleEntity> findAllByOrderByAlertTypeAsc();
}
