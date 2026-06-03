package com.echomind.console.alerts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AlertEventMapper extends MybatisPlusMapper<AlertEventEntity> {

    default List<AlertEventEntity> selectLatestOrderByCreatedAtDesc(int limit) {
        return selectList(Wrappers.lambdaQuery(AlertEventEntity.class)
            .orderByDesc(AlertEventEntity::getCreatedAt)
            .last("limit " + Math.max(1, limit)));
    }

    default long countByCreatedAtGreaterThanEqual(Instant createdAt) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .ge(AlertEventEntity::getCreatedAt, createdAt));
    }

    default boolean existsByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType,
                                                                           AlertStatus status,
                                                                           Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::getStatus, status)
            .ge(AlertEventEntity::getCreatedAt, since)) > 0;
    }

    default long countByAlertTypeAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType,
                                                                       AlertStatus status,
                                                                       Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::getStatus, status)
            .ge(AlertEventEntity::getCreatedAt, since));
    }

    default boolean existsByAlertTypeAndEscalatedTrueAndCreatedAtGreaterThanEqual(AlertType alertType, Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::isEscalated, true)
            .ge(AlertEventEntity::getCreatedAt, since)) > 0;
    }

    default boolean existsByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType,
                                                                                       String providerId,
                                                                                       AlertStatus status,
                                                                                       Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::getProviderId, providerId)
            .eq(AlertEventEntity::getStatus, status)
            .ge(AlertEventEntity::getCreatedAt, since)) > 0;
    }

    default long countByAlertTypeAndProviderIdAndStatusAndCreatedAtGreaterThanEqual(AlertType alertType,
                                                                                   String providerId,
                                                                                   AlertStatus status,
                                                                                   Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::getProviderId, providerId)
            .eq(AlertEventEntity::getStatus, status)
            .ge(AlertEventEntity::getCreatedAt, since));
    }

    default boolean existsByAlertTypeAndProviderIdAndEscalatedTrueAndCreatedAtGreaterThanEqual(AlertType alertType,
                                                                                              String providerId,
                                                                                              Instant since) {
        return selectCount(Wrappers.lambdaQuery(AlertEventEntity.class)
            .eq(AlertEventEntity::getAlertType, alertType)
            .eq(AlertEventEntity::getProviderId, providerId)
            .eq(AlertEventEntity::isEscalated, true)
            .ge(AlertEventEntity::getCreatedAt, since)) > 0;
    }
}
