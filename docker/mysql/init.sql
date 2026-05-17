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
    CONSTRAINT uq_skill_name_version UNIQUE (name, version)
) ENGINE=InnoDB;

CREATE INDEX IF NOT EXISTS idx_skill_state ON echomind.echomind_skills(state);

-- 用户账号表：第一阶段用于普通聊天会话隔离
CREATE TABLE IF NOT EXISTS echomind.echomind_users (
    user_id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
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

ALTER TABLE echomind.echomind_chat_sessions
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128) NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS idx_chat_session_user_activity
    ON echomind.echomind_chat_sessions(user_id, last_activity);

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

ALTER TABLE echomind.echomind_chat_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128) NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS idx_chat_msg_user_session_time
    ON echomind.echomind_chat_messages(user_id, session_id, timestamp);

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
    task LONGTEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    clarification_question LONGTEXT,
    clarification_answer LONGTEXT,
    clarification_stage VARCHAR(40),
    plan_review_json LONGTEXT,
    result_review_json LONGTEXT,
    final_output LONGTEXT,
    mermaid_diagram LONGTEXT,
    plan_retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_team_run_team (team_id),
    INDEX idx_team_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent Team Step 表：Planner 拆出的任务卡和 Executor 原始结果
CREATE TABLE IF NOT EXISTS echomind.echomind_agent_team_steps (
    step_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    step_index INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description LONGTEXT,
    required_capabilities_json LONGTEXT,
    acceptance_criteria LONGTEXT,
    assigned_agent_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    input_json LONGTEXT,
    raw_output LONGTEXT,
    previous_outputs_json LONGTEXT,
    revision_instructions LONGTEXT,
    review_status VARCHAR(32),
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
