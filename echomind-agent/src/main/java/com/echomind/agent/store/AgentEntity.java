package com.echomind.agent.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent持久化实体。
 *
 * <p>前端创建的Agent属于用户配置，必须持久化到MySQL，不能只放在运行时内存里。</p>
 */
@Entity
@Table(name = "echomind_agents")
@Getter
@Setter
public class AgentEntity {

    /** Agent唯一标识。 */
    @Id
    @Column(length = 128)
    private String agentId;

    /** Agent显示名称。 */
    @Column(nullable = false, length = 255)
    private String name;

    /** 系统提示词。 */
    @Column(nullable = false, length = 8000)
    private String systemPrompt;

    /** 默认模型ID，格式为providerId:modelName。 */
    @Column(nullable = false, length = 255)
    private String modelId;

    /** 绑定的Skill ID列表，JSON数组字符串。 */
    @Column(nullable = false, length = 4000)
    private String skillIdsJson;

    /** 创建时间。 */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 最后更新时间。 */
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
