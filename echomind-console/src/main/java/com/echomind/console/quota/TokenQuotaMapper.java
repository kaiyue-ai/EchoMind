package com.echomind.console.quota;

import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TokenQuotaMapper extends MybatisPlusMapper<TokenQuotaEntity> {
}
