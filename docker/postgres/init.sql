-- EchoMind PostgreSQL 初始化脚本
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 创建 Skill 表（如果 JPA 未自动创建）
CREATE TABLE IF NOT EXISTS echomind_skills (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    description VARCHAR(2000),
    parameter_schema_json VARCHAR(4000),
    dependencies_json VARCHAR(1000),
    author VARCHAR(255),
    tags_json VARCHAR(1000),
    state VARCHAR(20),
    jar_path VARCHAR(500),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uq_skill_name_version UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_skill_state ON echomind_skills(state);
