package com.echomind.console.sensitive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SensitiveRuleMapper extends MybatisPlusMapper<SensitiveRuleEntity> {

    default List<SensitiveRuleEntity> selectEnabledOrderByRuleNameAsc() {
        return selectList(Wrappers.lambdaQuery(SensitiveRuleEntity.class)
            .eq(SensitiveRuleEntity::isEnabled, true)
            .orderByAsc(SensitiveRuleEntity::getRuleName));
    }
}
