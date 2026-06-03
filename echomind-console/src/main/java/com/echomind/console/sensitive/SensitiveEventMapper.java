package com.echomind.console.sensitive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface SensitiveEventMapper extends MybatisPlusMapper<SensitiveEventEntity> {

    default List<SensitiveEventEntity> selectLatestOrderByCreatedAtDesc(int limit) {
        return selectList(Wrappers.lambdaQuery(SensitiveEventEntity.class)
            .orderByDesc(SensitiveEventEntity::getCreatedAt)
            .last("limit " + Math.max(1, limit)));
    }

    default long countByCreatedAtGreaterThanEqual(Instant createdAt) {
        return selectCount(Wrappers.lambdaQuery(SensitiveEventEntity.class)
            .ge(SensitiveEventEntity::getCreatedAt, createdAt));
    }
}
