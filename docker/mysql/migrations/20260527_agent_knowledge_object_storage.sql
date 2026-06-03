-- Store Agent knowledge original files in OSS/local object storage.
USE echomind;

SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_agent_knowledge_documents ADD COLUMN object_uri VARCHAR(512) NULL AFTER file_size',
        'SELECT ''echomind_agent_knowledge_documents.object_uri exists'''
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_agent_knowledge_documents'
      AND column_name = 'object_uri'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE echomind_agent_knowledge_documents ADD COLUMN content_type VARCHAR(128) NULL AFTER object_uri',
        'SELECT ''echomind_agent_knowledge_documents.content_type exists'''
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'echomind_agent_knowledge_documents'
      AND column_name = 'content_type'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
