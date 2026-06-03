package com.echomind.memory.knowledge.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** Agent 知识库文档 Mapper，对应表 {@code echomind_agent_knowledge_documents}。 */
@Mapper
public interface AgentKnowledgeDocumentMapper extends MybatisPlusMapper<AgentKnowledgeDocumentEntity> {

    /** 查询指定 Agent 的所有知识库文档。 */
    default List<AgentKnowledgeDocumentEntity> selectByAgentIdOrderByCreatedAtDesc(String agentId) {
        return selectList(Wrappers.lambdaQuery(AgentKnowledgeDocumentEntity.class)
            .eq(AgentKnowledgeDocumentEntity::getAgentId, agentId)
            .orderByDesc(AgentKnowledgeDocumentEntity::getCreatedAt));
    }
}
