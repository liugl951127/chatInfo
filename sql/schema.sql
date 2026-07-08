-- ============================================================
-- Online Chat v2.0.1 · 客户/坐席会话系统 数据库 schema
-- MySQL 8.0+ / MariaDB 10.5+
-- 字符集: utf8mb4 / 排序: utf8mb4_unicode_ci
--
-- v2.0.0 (基础 + 企业特性):
--   - 坐席技能 (skill_tags) + 技能路由
--   - 会话转接 (transferred_from_agent_id / transfer_reason)
--   - 已读回执 (message_receipt)
--   - CSAT 评价 (rating / rating_comment / rated_at)
--   - 快捷回复 (canned_response)
--   - 审计日志 (audit_log)
--
-- v2.0.1 (本次新增):
--   - chat_record          客户页面视频录制 (合规要求)
--   - chat_record_chunk    录制分片存储 (WebM, 按 sequence_no 排序)
--   - chat_audit_log       录制/转接/退出的独立审计日志 (与 audit_log 区分)
--     - actor_id / actor_role / ip / user_agent 区分于 audit_log.user_id
-- ============================================================

CREATE DATABASE IF NOT EXISTS `online_chat`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `online_chat`;

-- ============================================================
-- 用户表
-- ============================================================
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `username`     VARCHAR(64)  NOT NULL                COMMENT '登录名',
  `password`     VARCHAR(128) NOT NULL                COMMENT 'BCrypt 加密密码',
  `nickname`     VARCHAR(64)  NOT NULL                COMMENT '显示昵称',
  `role`         VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / AGENT / ADMIN',
  `skill_tags`   VARCHAR(255)     DEFAULT NULL        COMMENT '坐席技能, 逗号或JSON数组 (仅 AGENT, 演示用billing/refund/tech/general)',
  `avatar`       VARCHAR(255)     DEFAULT NULL        COMMENT '头像 URL',
  `status`       TINYINT      NOT NULL DEFAULT 1       COMMENT '1=启用 0=禁用',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表 (CUSTOMER/AGENT/ADMIN)';

-- ============================================================
-- 会话表
-- ============================================================
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
  `session_no`               VARCHAR(32)  NOT NULL,
  `customer_id`              BIGINT       NOT NULL,
  `agent_id`                 BIGINT           DEFAULT NULL,
  `skill_tag`                VARCHAR(32)      DEFAULT NULL        COMMENT '问题类型/技能标签',
  `status`                   VARCHAR(16)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/ACTIVE/CLOSED',
  `is_bot`                   TINYINT      NOT NULL DEFAULT 0       COMMENT '0=人工 1=智能客服 (bot 会话)',
  `transferred_from_agent_id` BIGINT          DEFAULT NULL        COMMENT '转接前的坐席',
  `transfer_reason`          VARCHAR(500)     DEFAULT NULL,
  `last_message`             VARCHAR(500)     DEFAULT NULL,
  `rating`                   TINYINT          DEFAULT NULL        COMMENT 'CSAT 1-5 星',
  `rating_comment`           VARCHAR(500)     DEFAULT NULL,
  `rated_at`                 DATETIME         DEFAULT NULL,
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `closed_at`                DATETIME         DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_agent_status` (`agent_id`, `status`),
  KEY `idx_status_updated` (`status`, `updated_at`),
  KEY `idx_skill` (`skill_tag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服会话表';

-- ============================================================
-- 消息表
-- ============================================================
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id`  BIGINT       NOT NULL,
  `sender_id`   BIGINT       NOT NULL,
  `sender_role` VARCHAR(16)  NOT NULL COMMENT 'CUSTOMER / AGENT / SYSTEM',
  `msg_type`    VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / IMAGE / FILE / SYSTEM / RECALL',
  `content`     TEXT         NOT NULL,
  `recalled`    TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常 1=已撤回',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session_created` (`session_id`, `created_at`),
  KEY `idx_sender` (`sender_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';

-- ============================================================
-- 消息已读回执
-- ============================================================
DROP TABLE IF EXISTS `message_receipt`;
CREATE TABLE `message_receipt` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `message_id` BIGINT       NOT NULL,
  `user_id`    BIGINT       NOT NULL,
  `read_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user` (`message_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息已读回执';

-- ============================================================
-- 快捷回复 (v2.0.0)
-- ============================================================
DROP TABLE IF EXISTS `canned_response`;
CREATE TABLE `canned_response` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `skill_tag`  VARCHAR(32)  DEFAULT NULL        COMMENT '按技能分类 (NULL=通用)',
  `title`      VARCHAR(64)  NOT NULL,
  `content`    TEXT         NOT NULL,
  `created_by` BIGINT       NOT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill` (`skill_tag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '快捷回复模板';

-- ============================================================
-- 审计日志 (v2.0.0) - cs-auth / cs-im 通用
-- ============================================================
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT       DEFAULT NULL,
  `action`     VARCHAR(32)  NOT NULL        COMMENT 'LOGIN / CREATE_SESSION / CLAIM / TRANSFER / CLOSE / RATE / RECALL ...',
  `target`     VARCHAR(64)  DEFAULT NULL,
  `detail`     VARCHAR(500) DEFAULT NULL,
  `ip`         VARCHAR(64)  DEFAULT NULL,
  `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_action` (`action`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通用审计日志';

-- ============================================================
-- v2.0.1 新增 - 客户页面视频录制 (合规要求)
--   每个录像对应一次录制会话, 包含元信息 + 同意标记
-- ============================================================
DROP TABLE IF EXISTS `chat_record`;
CREATE TABLE `chat_record` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id`    BIGINT       NOT NULL                COMMENT '所属会话 id',
  `user_id`       BIGINT       NOT NULL                COMMENT '被录制方 (客户) user_id',
  `user_role`     VARCHAR(16)  NOT NULL                COMMENT 'CUSTOMER / AGENT',
  `started_at`    DATETIME(3)  NOT NULL                COMMENT '开始时间',
  `ended_at`      DATETIME(3)      DEFAULT NULL        COMMENT '结束时间',
  `end_reason`    VARCHAR(32)      DEFAULT NULL        COMMENT 'NORMAL/USER_STOP/PAGE_CLOSE/PROCESS_KILLED/ERROR/SESSION_CLOSED',
  `chunk_count`   INT          NOT NULL DEFAULT 0      COMMENT '已上传分片数',
  `total_bytes`   BIGINT       NOT NULL DEFAULT 0      COMMENT '累计大小 (字节)',
  `consent_given` TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '合规: 是否获得用户明示同意 (0=否 1=是)',
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_started` (`started_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客户页面视频录像主表';

-- ============================================================
-- v2.0.1 新增 - 录像分片 (每片 5s WebM)
--   按 record_id + sequence_no 排序, 上传即落盘
-- ============================================================
DROP TABLE IF EXISTS `chat_record_chunk`;
CREATE TABLE `chat_record_chunk` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `record_id`    BIGINT       NOT NULL                COMMENT '所属录像 id',
  `sequence_no`  INT          NOT NULL                COMMENT '分片序号 (从 0 开始, 升序)',
  `mime_type`    VARCHAR(64)  NOT NULL DEFAULT 'video/webm',
  `duration_ms`  INT          NOT NULL DEFAULT 0      COMMENT '该分片时长 (ms)',
  `byte_size`    INT          NOT NULL DEFAULT 0      COMMENT '该分片大小 (字节)',
  `storage_path` VARCHAR(255) NOT NULL                COMMENT '落盘路径 <root>/<recordId>/<seq>-<uuid>.webm',
  `uploaded_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_seq` (`record_id`, `sequence_no`),
  KEY `idx_record` (`record_id`),
  CONSTRAINT `fk_chunk_record` FOREIGN KEY (`record_id`) REFERENCES `chat_record` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '录像分片';

-- ============================================================
-- v2.0.1 新增 - 录制/转接/退出的独立审计日志
--   与 audit_log 区分: 此表更聚焦合规场景 (RECORD_INIT/RECORD_END/RECORD_FORBIDDEN
--   /CUSTOMER_EXIT/CUSTOMER_TRANSFER 等), 含 user_agent 用于追溯浏览器环境
-- ============================================================
DROP TABLE IF EXISTS `chat_audit_log`;
CREATE TABLE `chat_audit_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `actor_id`   BIGINT       NOT NULL                COMMENT '操作人 id',
  `actor_role` VARCHAR(16)  NOT NULL                COMMENT '操作人角色 CUSTOMER/AGENT/ADMIN/SYSTEM',
  `action`     VARCHAR(64)  NOT NULL                COMMENT 'RECORD_INIT/RECORD_END/RECORD_DENY_NO_CONSENT/RECORD_FORBIDDEN/CUSTOMER_EXIT/CUSTOMER_TRANSFER',
  `target`     VARCHAR(128)     DEFAULT NULL        COMMENT '操作目标 (sessionId/recordId 等)',
  `detail`     TEXT             DEFAULT NULL        COMMENT '操作明细 (TEXT 支持更长记录)',
  `ip`         VARCHAR(45)      DEFAULT NULL        COMMENT '操作 IP (IPv4/IPv6)',
  `user_agent` VARCHAR(255)     DEFAULT NULL        COMMENT '浏览器 UA (前端录制场景)',
  `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_actor_time` (`actor_id`, `created_at`),
  KEY `idx_action_time` (`action`, `created_at`),
  KEY `idx_target` (`target`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天合规审计日志 (录制/转接/退出)';

-- ============================================================
-- 初始数据
-- ============================================================

-- 用户 (密码均为 123456, BCrypt $2a$ rounds=10)
-- Hash 验证: bcrypt.checkpw(b'123456', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW') == True
INSERT INTO `user` (`username`, `password`, `nickname`, `role`, `skill_tags`) VALUES
('admin',     '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '系统管理员', 'ADMIN',   NULL),
('customer1', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小明',       'CUSTOMER', NULL),
('customer2', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小红',       'CUSTOMER', NULL),
('agent1',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小张',   'AGENT',   'billing,refund'),
('agent2',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小李',   'AGENT',   'tech,general'),
('agent3',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小王',   'AGENT',   'general');

-- 快捷回复模板 (演示)
INSERT INTO `canned_response` (`skill_tag`, `title`, `content`, `created_by`) VALUES
(NULL,         '问候',     '您好, 很高兴为您服务, 请问有什么可以帮您?', 3),
(NULL,         '稍等',     '请稍等, 我帮您查询一下...', 3),
(NULL,         '结束',     '请问还有其他问题吗? 如果没有, 我可以结束本次会话了吗?', 3),
('billing',    '账单咨询', '您的账单明细可以通过 [账单页面] 查看, 如有疑问请提供订单号', 3),
('refund',     '退款说明', '退款将在 3-5 个工作日内原路退回, 请耐心等待', 4),
('tech',       '技术排查', '请提供您的设备型号和系统版本, 我帮您排查', 5);

-- 演示用空会话
INSERT INTO `chat_session` (`session_no`, `customer_id`, `status`, `skill_tag`)
VALUES ('S20260706001', 2, 'WAITING', 'general');

-- ============================================================
-- 验证脚本 (可选): 部署后可运行下面 SQL 检查表结构完整性
-- ============================================================
/*
SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH
FROM information_schema.tables
WHERE TABLE_SCHEMA = 'online_chat'
ORDER BY TABLE_NAME;

-- 期望 9 张表:
--   audit_log          - 通用审计 (login/close/...)
--   canned_response    - 快捷回复
--   chat_audit_log     - 合规审计 (record/transfer/exit)
--   chat_message       - 聊天消息
--   chat_record        - 录像主表
--   chat_record_chunk  - 录像分片
--   chat_session       - 会话
--   message_receipt    - 已读回执
--   user               - 用户
*/