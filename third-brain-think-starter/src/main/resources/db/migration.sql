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

-- AgentX 评测模块（agentx）三张表，源自 dodo-agentx/sql/init.sql
CREATE TABLE IF NOT EXISTS `agentx_conversation`  (
  `id` bigint NOT NULL COMMENT '主键ID',
  `conversation_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话窗口ID',
  `session_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次调用ID',
  `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户ID',
  `question` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户提问',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'running' COMMENT '执行状态: running/completed/interrupted/error',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `completed_at` timestamp NULL DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_conv_session`(`session_id` ASC) USING BTREE,
  INDEX `idx_conv_id`(`conversation_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AgentX会话窗口表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `agentx_session`  (
  `id` bigint NOT NULL COMMENT '主键ID',
  `conversation_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话窗口ID',
  `session_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次调用ID',
  `state_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态键: original_messages / working_messages / offload_context',
  `item_index` int NOT NULL DEFAULT 0 COMMENT '消息在状态键内的序号',
  `state_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息JSON（MessageJsonSerializer 序列化）',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_session_state`(`session_id` ASC, `state_key` ASC) USING BTREE,
  INDEX `idx_conv_state`(`conversation_id` ASC, `state_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AgentX会话消息链表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `agentx_trace`  (
  `id` bigint NOT NULL COMMENT '主键ID',
  `session_id` bigint NOT NULL COMMENT '会话记录ID（agentx_session.id）',
  `conversation_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话ID',
  `round` int NOT NULL COMMENT '本轮ReAct循环轮次',
  `input_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '输入内容（prompt/消息序列JSON）',
  `output_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '输出内容（模型回答/工具结果）',
  `think` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '模型思考内容',
  `prompt_tokens` int NULL DEFAULT 0 COMMENT '提示token数',
  `completion_tokens` int NULL DEFAULT 0 COMMENT '补全token数',
  `duration_ms` bigint NULL DEFAULT 0 COMMENT '本轮耗时（毫秒）',
  `success` int NULL DEFAULT 1 COMMENT '是否成功：1成功 0失败',
  `error_message` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '错误信息',
  `created_at` timestamp NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agentx_trace_session`(`session_id` ASC) USING BTREE,
  INDEX `idx_agentx_trace_conv`(`conversation_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE utf8mb4_general_ci COMMENT = 'AgentX追踪审计表' ROW_FORMAT = DYNAMIC;
