package com.echomind.console.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface AdminUserMapper extends MybatisPlusMapper<AdminUserEntity> {

    default Optional<AdminUserEntity> selectByUsername(String username) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(AdminUserEntity.class)
            .eq(AdminUserEntity::getUsername, username)
            .last("limit 1")));
    }

    default Optional<AdminUserEntity> selectByAdminIdAndStatus(String adminId, AdminUserStatus status) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(AdminUserEntity.class)
            .eq(AdminUserEntity::getAdminId, adminId)
            .eq(AdminUserEntity::getStatus, status)
            .last("limit 1")));
    }
}
