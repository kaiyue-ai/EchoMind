package com.echomind.agent.store;

import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent MySQL数据访问层。
 */
@Mapper
public interface AgentMapper extends MybatisPlusMapper<AgentEntity> {
}
