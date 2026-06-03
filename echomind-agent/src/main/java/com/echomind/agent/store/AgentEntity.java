package com.echomind.agent.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent持久化实体。
 *
 * <p>前端创建的Agent属于用户配置，必须持久化到MySQL，不能只放在运行时内存里。</p>
 */
@TableName("echomind_agents")
@Getter
@Setter
public class AgentEntity {

    /** Agent唯一标识。 */
    @TableId(value = "agent_id", type = IdType.INPUT)
    private String agentId;

    /** Agent显示名称。 */
    private String name;

    /** 系统提示词。 */
    @TableField("system_prompt")
    private String systemPrompt;

    /** 默认模型ID，格式为providerId:modelName。 */
    @TableField("model_id")
    private String modelId;

    /** 绑定的Skill ID列表，JSON数组字符串。 */
    @TableField("skill_ids_json")
    private String skillIdsJson;

    /** 创建时间。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 最后更新时间。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
