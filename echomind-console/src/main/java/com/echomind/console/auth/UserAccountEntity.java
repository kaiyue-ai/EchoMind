package com.echomind.console.auth;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 后台和普通用户登录账号。 */
@TableName("echomind_users")
@Getter
@Setter
public class UserAccountEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("avatar_uri")
    private String avatarUri;

    private UserAccountStatus status = UserAccountStatus.ACTIVE;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
