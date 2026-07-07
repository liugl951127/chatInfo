-- ============================================================
-- Online Chat v2.0.0 · 客户/坐席会话系统 数据库 schema
-- MySQL 8.0+ / MariaDB 10.5+
-- 字符集: utf8mb4 / 排序: utf8mb4_unicode_ci
--
-- v2.0.0 新增 (体验优化):
--   - 坐席技能 (skill_tags) + 技能路由
--   - 会话转接 (transferred_from_agent_id / transfer_reason)
--   - 已读回执 (message_receipt)
--   - CSAT 评价 (rating / rating_comment / rated_at)
--   - 快捷回复 (canned_response)
--   - 审计日志 (audit_log)
-- ============================================================

CREATE DATABASE IF NOT EXISTS `online_chat`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `online_chat`;

-- ============================================================
-- 用户表 (v2.0.0 加 skill_tags)
-- ============================================================
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `username`     VARCHAR(64)  NOT NULL                COMMENT '登录名',
  `password`     VARCHAR(128) NOT NULL                COMMENT 'BCrypt 加密密码',
  `nickname`     VARCHAR(64)  NOT NULL                COMMENT '显示昵称',
  `role`         VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / AGENT / ADMIN',
  `skill_tags`   VARCHAR(255)     DEFAULT NULL        COMMENT '坐席技能, 逗号分隔 (仅 AGENT)',
  `avatar`       VARCHAR(255)     DEFAULT NULL        COMMENT '头像 URL',
  `status`       TINYINT      NOT NULL DEFAULT 1       COMMENT '1=启用 0=禁用',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表 (CUSTOMER/AGENT/ADMIN)';

-- ============================================================
-- 会话表 (v2.0.0 加 skill_tag / 转接 / 评分字段)
-- ============================================================
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
  `session_no`               VARCHAR(32)  NOT NULL,
  `customer_id`              BIGINT       NOT NULL,
  `agent_id`                 BIGINT           DEFAULT NULL,
  `skill_tag`                VARCHAR(32)      DEFAULT NULL        COMMENT '问题类型/技能标签',
  `status`                   VARCHAR(16)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/ACTIVE/CLOSED',
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
-- 消息表 (v2.0.0 加 recalled 字段支持撤回)
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
-- 消息已读回执 (v2.0.0 新增)
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
-- 快捷回复 (v2.0.0 新增, 全局共享)
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
-- 审计日志 (v2.0.0 新增)
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计日志';

-- ============================================================
-- 初始数据
-- ============================================================

-- 用户 (密码均为 123456, BCrypt $2a$ rounds=10)
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