-- =============================================
-- RAG Demo Database Schema
-- Database: ardm
-- =============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for knowledge_document
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_document`;
CREATE TABLE `knowledge_document` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `doc_id` VARCHAR(64) NOT NULL COMMENT '文档唯一标识',
  `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
  `description` TEXT COMMENT '文档描述',
  `file_name` VARCHAR(255) NOT NULL COMMENT '文件名称',
  `file_path` VARCHAR(512) NOT NULL COMMENT '文件存储路径',
  `file_type` VARCHAR(50) COMMENT '文件类型(pdf/doc/docx/md/txt等)',
  `file_size` BIGINT COMMENT '文件大小(字节)',
  `knowledge_base_type` VARCHAR(50) NOT NULL DEFAULT 'DOCUMENT_SEARCH' COMMENT '知识库类型: DOCUMENT_SEARCH-文档搜索, DATA_QUERY-数据查询',
  `table_name` VARCHAR(100) COMMENT '数据表名称(仅DATA_QUERY模式使用)',
  `upload_user` VARCHAR(100) NOT NULL COMMENT '上传用户',
  `accessible_by` VARCHAR(500) COMMENT '可见范围，多个团队用逗号分隔',
  `status` VARCHAR(50) NOT NULL DEFAULT 'UPLOADED' COMMENT '文档状态: UPLOADED-已上传, PARSED-已解析, SPLITTED-已切片, EMBEDDED-已向量化, FAILED-失败',
  `error_message` TEXT COMMENT '错误信息',
  `segment_count` INT DEFAULT 0 COMMENT '切片数量',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_id` (`doc_id`),
  KEY `idx_upload_user` (`upload_user`),
  KEY `idx_status` (`status`),
  KEY `idx_knowledge_base_type` (`knowledge_base_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档表';

-- ----------------------------
-- Table structure for knowledge_segment
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_segment`;
CREATE TABLE `knowledge_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `segment_id` VARCHAR(64) NOT NULL COMMENT '片段唯一标识',
  `doc_id` VARCHAR(64) NOT NULL COMMENT '所属文档ID',
  `content` TEXT NOT NULL COMMENT '片段内容',
  `segment_index` INT NOT NULL COMMENT '片段索引序号',
  `split_type` VARCHAR(50) COMMENT '切片方式: LENGTH/TITLE/REGEX/SMART/SEPARATOR',
  `metadata` JSON COMMENT '元数据(JSON格式)，包含标题、页码等信息',
  `status` VARCHAR(50) NOT NULL DEFAULT 'CREATED' COMMENT '片段状态: CREATED-已创建, EMBEDDED-已向量化, FAILED-失败',
  `embedding_model` VARCHAR(100) COMMENT '使用的Embedding模型',
  `vector_id` VARCHAR(128) COMMENT '向量数据库中的ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_segment_id` (`segment_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_status` (`status`),
  KEY `idx_segment_index` (`segment_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识片段表';

-- ----------------------------
-- Table structure for chat_conversation
-- ----------------------------
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE `chat_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '会话唯一标识(UUID)',
  `user_id` VARCHAR(100) NOT NULL COMMENT '用户ID',
  `title` VARCHAR(255) COMMENT '会话标题',
  `status` VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态: ACTIVE-活跃, ARCHIVED-已归档, DELETED-已删除',
  `message_count` INT DEFAULT 0 COMMENT '消息数量',
  `last_message_time` DATETIME COMMENT '最后一条消息时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话会话表';

-- ----------------------------
-- Table structure for chat_message
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `message_id` VARCHAR(64) NOT NULL COMMENT '消息唯一标识(UUID)',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '所属会话ID',
  `type` VARCHAR(20) NOT NULL COMMENT '消息类型: USER-用户, ASSISTANT-AI助手',
  `content` TEXT NOT NULL COMMENT '消息内容',
  `metadata` JSON COMMENT '元数据(JSON格式)，包含token数、耗时等',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_type` (`type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话消息表';

SET FOREIGN_KEY_CHECKS = 1;
