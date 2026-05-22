package com.echomind.console.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 项目三管理端账号，和客户端用户账号物理隔离。 */
@Entity
@Table(name = "echomind_admin_users")
@Getter
@Setter
public class AdminUserEntity {

    @Id
    @Column(name = "admin_id", length = 128)
    private String adminId;

    @Column(nullable = false, unique = true, length = 128)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AdminUserStatus status = AdminUserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
