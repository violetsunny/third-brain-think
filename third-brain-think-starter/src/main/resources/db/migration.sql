-- rag-demo enhancement migrations
-- Run these in order against the `ardm` database

-- Task 1.2: Document version table
CREATE TABLE IF NOT EXISTS `knowledge_document_version` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `version_id`   VARCHAR(64)  NOT NULL COMMENT '版本ID (UUID)',
    `doc_id`       VARCHAR(64)  NOT NULL COMMENT '文档ID，关联 knowledge_document.doc_id',
    `version`      INT          NOT NULL DEFAULT 1 COMMENT '版本号，从1自增',
    `content_hash` VARCHAR(64)  NOT NULL COMMENT '文件内容 SHA-256',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE / INACTIVE',
    `changelog`    TEXT         NULL COMMENT '版本说明',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version_id` (`version_id`),
    KEY `idx_doc_id` (`doc_id`),
    KEY `idx_content_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本管理';

-- Task 1.3: Add current_version_id to knowledge_document
ALTER TABLE `knowledge_document`
    ADD COLUMN IF NOT EXISTS `current_version_id` VARCHAR(64) NULL COMMENT '当前激活版本ID' AFTER `status`;

-- Task 1.4: Add rag_references column to chat_message
ALTER TABLE `chat_message`
    ADD COLUMN IF NOT EXISTS `rag_references` TEXT NULL COMMENT 'RAG检索来源引用 JSON' AFTER `content`;
