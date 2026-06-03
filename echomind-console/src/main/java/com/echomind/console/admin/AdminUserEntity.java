package com.echomind.console.admin;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 项目三管理端账号，和客户端用户账号物理隔离。 */
@TableName("echomind_admin_users")
@Getter
@Setter
public class AdminUserEntity {

    @TableId(value = "admin_id", type = IdType.INPUT)
    private String adminId;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    private AdminUserStatus status = AdminUserStatus.ACTIVE;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
