package com.echomind.console.sensitive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SensitiveRuleRepository extends JpaRepository<SensitiveRuleEntity, String> {

    List<SensitiveRuleEntity> findByEnabledTrueOrderByRuleNameAsc();
}
