-- 文档表
CREATE TABLE IF NOT EXISTS `documents` (
    `id` BIGINT(20) NOT NULL COMMENT '文档ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '上传用户ID',
    `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
    `type` VARCHAR(50) NOT NULL COMMENT '文档类型: research_paper, teaching_material, other',
    `file_url` VARCHAR(500) NOT NULL COMMENT 'MinIO文件路径',
    `file_size` BIGINT COMMENT '文件大小(字节)',
    `status` VARCHAR(20) COMMENT '处理状态: pending, processing, completed, failed',
    `metadata` JSON COMMENT '文档元数据',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否删除(0:否 1:是)',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_user_id` (`user_id`) USING BTREE,
    INDEX `idx_type` (`type`) USING BTREE,
    INDEX `idx_status` (`status`) USING BTREE,
    INDEX `idx_create_time` (`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档表';

-- 文档块表
CREATE TABLE IF NOT EXISTS `document_chunks` (
    `id` BIGINT(20) NOT NULL COMMENT '块ID',
    `document_id` BIGINT(20) NOT NULL COMMENT '文档ID',
    `chunk_index` INT NOT NULL COMMENT '块索引',
    `content` TEXT NOT NULL COMMENT '文本内容',
    `vector_id` VARCHAR(100) COMMENT 'Redis向量ID',
    `tokens` INT COMMENT 'Token数量',
    `metadata` JSON COMMENT '块元数据',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_document_id` (`document_id`) USING BTREE,
    FOREIGN KEY (`document_id`) REFERENCES `documents`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档块表';

-- RAG 查询日志表
CREATE TABLE IF NOT EXISTS `rag_query_logs` (
    `id` BIGINT(20) NOT NULL COMMENT '日志ID',
    `user_id` BIGINT(20) COMMENT '用户ID',
    `conversation_id` VARCHAR(64) COMMENT '会话ID',
    `query` TEXT NOT NULL COMMENT '用户查询',
    `retrieved_docs` JSON COMMENT '检索到的文档ID和分数',
    `response` TEXT COMMENT 'AI响应',
    `retrieval_time` INT COMMENT '检索耗时(ms)',
    `generation_time` INT COMMENT '生成耗时(ms)',
    `total_tokens` INT COMMENT '消耗Token数',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_user_id` (`user_id`) USING BTREE,
    INDEX `idx_conversation_id` (`conversation_id`) USING BTREE,
    INDEX `idx_create_time` (`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG查询日志表';

-- 教学设计表
CREATE TABLE IF NOT EXISTS `teaching_plans` (
    `id` BIGINT(20) NOT NULL COMMENT '教案ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '创建用户ID',
    `title` VARCHAR(255) NOT NULL COMMENT '课题名称',
    `grade` VARCHAR(50) COMMENT '学段',
    `subject` VARCHAR(50) COMMENT '学科',
    `content` JSON COMMENT '教案内容(结构化)',
    `source_papers` JSON COMMENT '参考论文ID列表',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_user_id` (`user_id`) USING BTREE,
    INDEX `idx_grade` (`grade`) USING BTREE,
    INDEX `idx_subject` (`subject`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教学设计表';

