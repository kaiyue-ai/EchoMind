package com.echomind.console.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProviderTokenBudgetMapper extends MybatisPlusMapper<ProviderTokenBudgetEntity> {

    default List<ProviderTokenBudgetEntity> selectAllOrderByProviderIdAsc() {
        return selectList(Wrappers.lambdaQuery(ProviderTokenBudgetEntity.class)
            .orderByAsc(ProviderTokenBudgetEntity::getProviderId));
    }
}
