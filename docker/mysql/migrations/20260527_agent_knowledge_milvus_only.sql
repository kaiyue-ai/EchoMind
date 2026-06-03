-- Align vector ownership with the current architecture:
-- MySQL stores chat history plus knowledge document/chunk text.
-- Milvus stores user long-term facts and Agent knowledge vectors.

USE echomind;

SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'SELECT ''echomind_agent_knowledge_chunks.embedding_json already removed''',
        'ALTER TABLE echomind_agent_knowledge_chunks DROP COLUMN embedding_json'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_agent_knowledge_chunks'
      AND column_name = 'embedding_json'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DROP TABLE IF EXISTS echomind_memory_embeddings;
