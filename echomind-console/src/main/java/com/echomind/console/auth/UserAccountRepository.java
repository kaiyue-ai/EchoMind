package com.echomind.console.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 用户账号 Repository。 */
public interface UserAccountRepository extends JpaRepository<UserAccountEntity, String> {

    Optional<UserAccountEntity> findByUsername(String username);

    Optional<UserAccountEntity> findByUserIdAndStatus(String userId, UserAccountStatus status);
}
