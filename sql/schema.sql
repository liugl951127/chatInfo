-- ============================================================
-- Online Chat · 客户/坐席会话系统 数据库 schema
-- MySQL 8.0+
-- 字符集: utf8mb4 / 排序: utf8mb4_unicode_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS `online_chat`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `online_chat`;

-- ------------------------------------------------------------
-- 用户表 (客户 + 坐席共表, 通过 role 区分)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`     VARCHAR(64)  NOT NULL                COMMENT '登录名',
  `password`     VARCHAR(128) NOT NULL                COMMENT 'BCrypt 加密密码',
  `nickname`     VARCHAR(64)  NOT NULL                COMMENT '显示昵称',
  `role`         VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / AGENT',
  `avatar`       VARCHAR(255)          DEFAULT NULL   COMMENT '头像 URL',
  `status`       TINYINT      NOT NULL DEFAULT 1       COMMENT '1=启用 0=禁用',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表(客户/坐席)';

-- ------------------------------------------------------------
-- 会话表
-- 一次会话 = 一个客户对一个坐席(可切换坐席)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_no`    VARCHAR(32)  NOT NULL                COMMENT '会话编号 (业务展示用)',
  `customer_id`   BIGINT       NOT NULL                COMMENT '客户 user.id',
  `agent_id`      BIGINT           DEFAULT NULL        COMMENT '坐席 user.id, 未分配时为 NULL',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/ACTIVE/CLOSED',
  `last_message`  VARCHAR(500)     DEFAULT NULL        COMMENT '最后一条消息摘要',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `closed_at`     DATETIME         DEFAULT NULL        COMMENT '关闭时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_agent_status` (`agent_id`, `status`),
  KEY `idx_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服会话表';

-- ------------------------------------------------------------
-- 消息表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id`  BIGINT       NOT NULL                COMMENT '所属会话',
  `sender_id`   BIGINT       NOT NULL                COMMENT '发送者 user.id',
  `sender_role` VARCHAR(16)  NOT NULL                COMMENT 'CUSTOMER / AGENT / SYSTEM',
  `msg_type`    VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / IMAGE / FILE / SYSTEM',
  `content`     TEXT         NOT NULL                COMMENT '消息内容',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session_created` (`session_id`, `created_at`),
  KEY `idx_sender` (`sender_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';

-- ------------------------------------------------------------
-- 初始数据
-- 密码统一为: 123456 (BCrypt, $2a$, rounds=10)
-- 此 hash 由 Python bcrypt.hashpw(b'123456', bcrypt.gensalt(10)) 生成
-- 已验证: bcrypt.checkpw(b'123456', hash) -> True
-- ------------------------------------------------------------
INSERT INTO `user` (`username`, `password`, `nickname`, `role`) VALUES
('customer1', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小明',     'CUSTOMER'),
('customer2', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小红',     'CUSTOMER'),
('agent1',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小张', 'AGENT'),
('agent2',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小李', 'AGENT');

-- 演示用一条空会话
INSERT INTO `chat_session` (`session_no`, `customer_id`, `status`)
VALUES ('S20260706001', 1, 'WAITING');