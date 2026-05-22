package com.echomind.console.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity, String> {

    Optional<AdminUserEntity> findByUsername(String username);

    Optional<AdminUserEntity> findByAdminIdAndStatus(String adminId, AdminUserStatus status);
}
