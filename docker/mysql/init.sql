-- EchoMind MySQL 初始化脚本
CREATE DATABASE IF NOT EXISTS echomind CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS swtest CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建 Skill 表（如果 JPA 未自动创建）
CREATE TABLE IF NOT EXISTS echomind.echomind_skills (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    parameter_schema_json VARCHAR(4000),
    dependencies_json VARCHAR(1000),
    author VARCHAR(255),
    tags_json VARCHAR(1000),
    state VARCHAR(20),
    jar_path VARCHAR(255),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    INDEX idx_skill_state (state),
    CONSTRAINT uq_skill_name_version UNIQUE (name, version)
) ENGINE=InnoDB;

-- 用户账号表：第一阶段用于普通聊天会话隔离
CREATE TABLE IF NOT EXISTS echomind.echomind_users (
    user_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    avatar_uri VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 项目三管理端账号表：和客户端用户账号隔离
CREATE TABLE IF NOT EXISTS echomind.echomind_admin_users (
    admin_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 客户端 AI 调用审计表：管理端按用户聚合 Trace 和 Token
CREATE TABLE IF NOT EXISTS echomind.echomind_ai_call_usage (
    id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    user_id VARCHAR(128) NOT NULL,
    username VARCHAR(128),
    account_type VARCHAR(32) NOT NULL DEFAULT 'client',
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    model_id VARCHAR(255),
    operation VARCHAR(64) NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    usage_source VARCHAR(32) NOT NULL DEFAULT 'PROVIDER',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_ai_usage_user_time (user_id, created_at),
    INDEX idx_ai_usage_trace (trace_id),
    INDEX idx_ai_usage_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Token 配额表：管理端配置用户级日/月限额，调用前基于真实 PROVIDER 用量拦截
CREATE TABLE IF NOT EXISTS echomind.echomind_token_quotas (
    user_id VARCHAR(128) PRIMARY KEY,
    daily_limit_tokens BIGINT,
    monthly_limit_tokens BIGINT,
    warning_threshold_percent INT NOT NULL DEFAULT 80,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_token_quotas_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 项目三治理：敏感数据规则和事件，只保存脱敏后的样本
CREATE TABLE IF NOT EXISTS echomind.echomind_sensitive_rules (
    rule_id VARCHAR(128) PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    pattern VARCHAR(1000) NOT NULL,
    replacement VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL DEFAULT 'MASK',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_sensitive_rules_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind.echomind_sensitive_events (
    event_id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(64),
    user_id VARCHAR(128),
    username VARCHAR(128),
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    rule_id VARCHAR(128),
    rule_name VARCHAR(128),
    direction VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    match_count INT NOT NULL DEFAULT 0,
    sample VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_sensitive_events_time (created_at),
    INDEX idx_sensitive_events_trace (trace_id),
    INDEX idx_sensitive_events_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 项目三治理：告警规则和事件，飞书 webhook 地址可通过配置或单规则覆盖
CREATE TABLE IF NOT EXISTS echomind.echomind_alert_rules (
    rule_id VARCHAR(128) PRIMARY KEY,
    alert_type VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL DEFAULT 'WARNING',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    threshold_percent DOUBLE,
    window_minutes INT,
    quiet_minutes INT NOT NULL DEFAULT 30,
    webhook_url VARCHAR(1000),
    escalation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    escalation_threshold INT NOT NULL DEFAULT 3,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_alert_rules_type (alert_type),
    INDEX idx_alert_rules_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS echomind.echomind_alert_events (
    event_id VARCHAR(128) PRIMARY KEY,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64),
    user_id VARCHAR(128),
    username VARCHAR(128),
    agent_id VARCHAR(128),
    session_id VARCHAR(128),
    title VARCHAR(255) NOT NULL,
    message VARCHAR(2000),
    suggestion VARCHAR(1000),
    failure_reason VARCHAR(1000),
    escalated BOOLEAN NOT NULL DEFAULT FALSE,
    suppressed_count INT NOT NULL DEFAULT 0,
    provider_response VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_alert_events_time (created_at),
    INDEX idx_alert_events_type_time (alert_type, created_at),
    INDEX idx_alert_events_trace (trace_id),
    INDEX idx_alert_events_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建 Agent 表（生产环境 ddl-auto=validate，需要初始化脚本兜底）
CREATE TABLE IF NOT EXISTS echomind.echomind_agents (
    agent_id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    system_prompt VARCHAR(8000) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    skill_ids_json VARCHAR(4000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话记忆主表：完整历史事实来源的一部分
CREATE TABLE IF NOT EXISTS echomind.echomind_chat_sessions (
    user_id VARCHAR(128) NOT NULL DEFAULT 'default',
    session_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128),
    title VARCHAR(255),
    summary LONGTEXT,
    message_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_activity DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, session_id),
    INDEX idx_chat_session_last_activity (last_activity),
    INDEX idx_chat_session_user_activity (user_id, last_activity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话消息表：前端历史记录从这里完整读取
CREATE TABLE IF NOT EXISTS echomind.echomind_chat_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'default',
    role VARCHAR(32) NOT NULL,
    content LONGTEXT,
    timestamp DATETIME(6) NOT NULL,
    metadata_json LONGTEXT,
    attachments_json LONGTEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chat_msg_session_time (session_id, timestamp),
    INDEX idx_chat_msg_session_role (session_id, role),
    INDEX idx_chat_msg_user_session_time (user_id, session_id, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话向量表：MySQL 作为 Redis Stack 向量索引的持久备份和兜底检索
CREATE TABLE IF NOT EXISTS echomind.echomind_memory_embeddings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(128) NOT NULL,
    message_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_preview VARCHAR(1000),
    embedding_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_memory_embedding_session (session_id),
    INDEX idx_memory_embedding_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 私有知识库文档表：每个 Agent 独立管理上传的 txt/pdf
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_agent_knowledge_doc_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 私有知识库切片表：MySQL 保存原文切片和向量备份，Redis Stack 负责在线 KNN 检索
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_knowledge_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id VARCHAR(128) NOT NULL,
    document_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content LONGTEXT NOT NULL,
    embedding_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_agent_knowledge_chunk_agent (agent_id),
    INDEX idx_agent_knowledge_chunk_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team 定义表：团队配置的事实来源
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_teams (
    team_id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team 成员表：角色、Agent、能力标签和排序
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_team_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    team_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    capability_tags_json LONGTEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_team_member_team (team_id),
    INDEX idx_team_member_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team Run 表：一次协作任务的黑板首页
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_team_runs (
    run_id VARCHAR(128) PRIMARY KEY,
    team_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL DEFAULT 'default',
    task LONGTEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    task_level VARCHAR(32) NOT NULL DEFAULT 'COMPLEX',
    clarification_question LONGTEXT,
    clarification_answer LONGTEXT,
    clarification_stage VARCHAR(40),
    plan_review_json LONGTEXT,
    result_review_json LONGTEXT,
    merge_output LONGTEXT,
    global_review_json LONGTEXT,
    conflict_report_json LONGTEXT,
    arbitration_json LONGTEXT,
    final_output LONGTEXT,
    mermaid_diagram LONGTEXT,
    plan_retry_count INT NOT NULL DEFAULT 0,
    result_replan_count INT NOT NULL DEFAULT 0,
    partial_replan_count INT NOT NULL DEFAULT 0,
    full_replan_count INT NOT NULL DEFAULT 0,
    arbitration_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_team_run_team (team_id),
    INDEX idx_team_run_user (user_id),
    INDEX idx_team_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team Step 表：Planner 拆出的任务卡和 Executor 原始结果
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_team_steps (
    step_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    step_index INT NOT NULL,
    client_step_id VARCHAR(128),
    title VARCHAR(255) NOT NULL,
    description LONGTEXT,
    required_capabilities_json LONGTEXT,
    depends_on_step_ids_json LONGTEXT,
    risk_level VARCHAR(32) NOT NULL DEFAULT 'LOW',
    risk_reason LONGTEXT,
    acceptance_criteria LONGTEXT,
    assigned_agent_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    input_json LONGTEXT,
    raw_output LONGTEXT,
    previous_outputs_json LONGTEXT,
    revision_instructions LONGTEXT,
    review_status VARCHAR(32),
    quality_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    sub_review_json LONGTEXT,
    last_review_reason LONGTEXT,
    reflection_json LONGTEXT,
    plan_iteration INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_team_step_run (run_id),
    INDEX idx_team_step_status (status),
    INDEX idx_team_step_agent (assigned_agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team Event 表：协作事件流，前端时间线和 Mermaid 的事实来源
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_team_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    step_id VARCHAR(128),
    type VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32),
    actor_agent_id VARCHAR(128),
    message LONGTEXT,
    payload_json LONGTEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_team_event_run (run_id, created_at),
    INDEX idx_team_event_step (step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
