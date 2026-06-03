package com.echomind.console.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/** 用户账号 Mapper，对应表 {@code echomind_users}。 */
@Mapper
public interface UserAccountMapper extends MybatisPlusMapper<UserAccountEntity> {

    default Optional<UserAccountEntity> selectByUsername(String username) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(UserAccountEntity.class)
            .eq(UserAccountEntity::getUsername, username)
            .last("limit 1")));
    }

    default Optional<UserAccountEntity> selectByUserIdAndStatus(String userId, UserAccountStatus status) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(UserAccountEntity.class)
            .eq(UserAccountEntity::getUserId, userId)
            .eq(UserAccountEntity::getStatus, status)
            .last("limit 1")));
    }

    default long countByStatus(UserAccountStatus status) {
        return selectCount(Wrappers.lambdaQuery(UserAccountEntity.class)
            .eq(UserAccountEntity::getStatus, status));
    }
}
