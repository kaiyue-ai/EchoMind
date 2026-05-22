package com.echomind.console.quota;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenQuotaRepository extends JpaRepository<TokenQuotaEntity, String> {
}
