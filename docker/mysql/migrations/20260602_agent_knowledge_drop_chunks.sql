-- Agent knowledge chunks are now stored and retrieved from Milvus.
-- MySQL keeps only document metadata and original object references.

USE echomind;

DROP TABLE IF EXISTS echomind_agent_knowledge_chunks;
