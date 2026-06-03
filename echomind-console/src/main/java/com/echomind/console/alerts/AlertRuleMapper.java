package com.echomind.console.alerts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AlertRuleMapper extends MybatisPlusMapper<AlertRuleEntity> {

    default Optional<AlertRuleEntity> selectOneByAlertType(AlertType alertType) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(AlertRuleEntity.class)
            .eq(AlertRuleEntity::getAlertType, alertType)
            .last("limit 1")));
    }

    default List<AlertRuleEntity> selectAllOrderByAlertTypeAsc() {
        return selectList(Wrappers.lambdaQuery(AlertRuleEntity.class)
            .orderByAsc(AlertRuleEntity::getAlertType));
    }
}
