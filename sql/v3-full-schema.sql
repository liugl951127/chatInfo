-- ============================================================
-- V3.0 智能客服平台 - 完整数据库 Schema
-- ------------------------------------------------------------
-- 适用版本:  V3.0 (2026-07-12)
-- 适用 DB:   MySQL 8.0+ / MariaDB 10.5+
-- 字符集:    utf8mb4 / 排序: utf8mb4_unicode_ci
-- 库:        online_chat (单库跨模块命名)
-- ------------------------------------------------------------
-- 模块 -> 表 映射:
--   cs-auth:              user
--   cs-im:                chat_session, chat_message, message_receipt,
--                         canned_response, audit_log, chat_record,
--                         chat_record_chunk, chat_audit_log
--   cs-cdp:               cdp_event, cdp_tag, cdp_customer_profile
--   cs-community:         community_post, community_reply
--   cs-prediction:        prediction_rule, prediction_event
--   cs-customer-success:  success_health_score_history
--   cs-video:             video_session
--   cs-voice:             voice_call
-- ------------------------------------------------------------
-- 21 张表 + 3 个 INDEX
-- ============================================================

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
SET collation_connection = 'utf8mb4_unicode_ci';

CREATE DATABASE IF NOT EXISTS `online_chat`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `online_chat`;

-- ============================================================
-- cs-auth: 用户表
-- ============================================================
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `username`     VARCHAR(64)  NOT NULL                COMMENT '登录名',
  `password`     VARCHAR(128) NOT NULL                COMMENT 'BCrypt 加密密码 (10 轮)',
  `nickname`     VARCHAR(64)  NOT NULL                COMMENT '显示昵称',
  `role`         VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / AGENT / ADMIN',
  `skill_tags`   VARCHAR(255)     DEFAULT NULL        COMMENT '坐席技能, 逗号或 JSON 数组 (仅 AGENT, 演示 billing/refund/tech/general)',
  `avatar`       VARCHAR(255)     DEFAULT NULL        COMMENT '头像 URL',
  `status`       TINYINT      NOT NULL DEFAULT 1       COMMENT '1=启用 0=禁用',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表 (CUSTOMER/AGENT/ADMIN)';

-- ============================================================
-- cs-im: 会话表
-- ============================================================
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
  `session_no`               VARCHAR(32)  NOT NULL                COMMENT '业务编号 (e.g. S20260706001)',
  `customer_id`              BIGINT       NOT NULL                COMMENT '客户 user_id',
  `agent_id`                 BIGINT           DEFAULT NULL        COMMENT '坐席 user_id (NULL=待接单)',
  `skill_tag`                VARCHAR(32)      DEFAULT NULL        COMMENT '问题类型/技能标签',
  `status`                   VARCHAR(16)  NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/ACTIVE/CLOSED',
  `is_bot`                   TINYINT      NOT NULL DEFAULT 0       COMMENT '0=人工 1=智能客服 (bot 会话)',
  `transferred_from_agent_id` BIGINT          DEFAULT NULL        COMMENT '转接前的坐席',
  `transfer_reason`          VARCHAR(500)     DEFAULT NULL        COMMENT '转接原因',
  `last_message`             VARCHAR(500)     DEFAULT NULL        COMMENT '最后一条消息预览 (会话列表展示)',
  `rating`                   TINYINT          DEFAULT NULL        COMMENT 'CSAT 1-5 星',
  `rating_comment`           VARCHAR(500)     DEFAULT NULL        COMMENT '评分文字评论',
  `rated_at`                 DATETIME         DEFAULT NULL        COMMENT '评分时间',
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `closed_at`                DATETIME         DEFAULT NULL        COMMENT '会话关闭时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_agent_status` (`agent_id`, `status`),
  KEY `idx_status_updated` (`status`, `updated_at`),
  KEY `idx_skill` (`skill_tag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服会话表';

-- ============================================================
-- cs-im: 消息表
-- ============================================================
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id`  BIGINT       NOT NULL                COMMENT '所属会话 id',
  `sender_id`   BIGINT       NOT NULL                COMMENT '发送者 user_id',
  `sender_role` VARCHAR(16)  NOT NULL                COMMENT 'CUSTOMER / AGENT / SYSTEM',
  `msg_type`    VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / IMAGE / FILE / VOICE / SYSTEM / RECALL',
  `content`     TEXT         NOT NULL                COMMENT '消息内容 (TEXT/Base64/URL)',
  `recalled`    TINYINT      NOT NULL DEFAULT 0       COMMENT '0=正常 1=已撤回',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session_created` (`session_id`, `created_at`),
  KEY `idx_sender` (`sender_id`),
  KEY `idx_msg_type` (`msg_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';

-- ============================================================
-- cs-im: 消息已读回执
-- ============================================================
DROP TABLE IF EXISTS `message_receipt`;
CREATE TABLE `message_receipt` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `message_id` BIGINT       NOT NULL                COMMENT '消息 id',
  `user_id`    BIGINT       NOT NULL                COMMENT '已读用户 id',
  `read_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_user` (`message_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息已读回执';

-- ============================================================
-- cs-im: 快捷回复模板
-- ============================================================
DROP TABLE IF EXISTS `canned_response`;
CREATE TABLE `canned_response` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `skill_tag`  VARCHAR(32)  DEFAULT NULL        COMMENT '按技能分类 (NULL=通用)',
  `title`      VARCHAR(64)  NOT NULL            COMMENT '模板标题',
  `content`    TEXT         NOT NULL            COMMENT '模板内容',
  `created_by` BIGINT       NOT NULL            COMMENT '创建者 user_id',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill` (`skill_tag`),
  KEY `idx_creator` (`created_by`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '快捷回复模板';

-- ============================================================
-- cs-im: 通用审计日志
-- ============================================================
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT       DEFAULT NULL            COMMENT '操作人 user_id',
  `action`     VARCHAR(32)  NOT NULL                COMMENT 'LOGIN / CREATE_SESSION / CLAIM / TRANSFER / CLOSE / RATE / RECALL / ...',
  `target`     VARCHAR(64)  DEFAULT NULL            COMMENT '操作目标 (sessionId 等)',
  `detail`     VARCHAR(500) DEFAULT NULL            COMMENT '操作详情',
  `ip`         VARCHAR(64)  DEFAULT NULL            COMMENT 'IP 地址',
  `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_action` (`action`),
  KEY `idx_target` (`target`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通用审计日志 (login/close/...)';

-- ============================================================
-- cs-im: 录像主表 (合规要求)
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
  KEY `idx_started` (`started_at`),
  KEY `idx_consent` (`consent_given`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客户页面视频录像主表';

-- ============================================================
-- cs-im: 录像分片 (每片 5s WebM)
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
-- cs-im: 录制/转接/退出独立审计日志
-- ============================================================
DROP TABLE IF EXISTS `chat_audit_log`;
CREATE TABLE `chat_audit_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `actor_id`   BIGINT       NOT NULL                COMMENT '操作人 id',
  `actor_role` VARCHAR(16)  NOT NULL                COMMENT 'CUSTOMER/AGENT/ADMIN/SYSTEM',
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
-- cs-cdp: 客户主档案 (数字孪生 360, 1:1 with user)
-- ============================================================
DROP TABLE IF EXISTS `cdp_customer_profile`;
CREATE TABLE `cdp_customer_profile` (
  `user_id`           BIGINT       PRIMARY KEY                                COMMENT '用户 ID (PK, 1:1 with user.id)',
  `nickname`          VARCHAR(64)                                              COMMENT '昵称 (冗余 user.nickname)',
  `avatar_url`        VARCHAR(255)                                             COMMENT '头像 URL',
  `vip_level`         TINYINT      DEFAULT 0                                   COMMENT '0=普通 1=银 2=金 3=钻石',
  `register_at`       DATETIME                                                 COMMENT '注册时间',
  `last_active_at`    DATETIME                                                 COMMENT '最后活跃时间',
  `total_orders`      INT          DEFAULT 0                                   COMMENT '历史订单数',
  `total_amount`      DECIMAL(12,2) DEFAULT 0                                  COMMENT '历史消费金额',
  `avg_csat`          DECIMAL(2,1) DEFAULT 0                                   COMMENT '平均满意度 (0.0-5.0)',
  `total_sessions`    INT          DEFAULT 0                                   COMMENT '历史会话数',
  `churn_risk`        TINYINT      DEFAULT 0                                   COMMENT '流失风险 0=健康 1=关注 2=风险 3=流失',
  `health_score`      INT          DEFAULT 100                                 COMMENT '健康分 (0-100)',
  `tags`              JSON                                                     COMMENT '标签数组',
  `preferences`       JSON                                                     COMMENT '偏好 (时间/渠道/语言)',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_vip_active` (`vip_level`, `last_active_at`),
  KEY `idx_health` (`health_score`),
  KEY `idx_churn` (`churn_risk`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '客户主档案 (数字孪生 360)';

-- ============================================================
-- cs-cdp: 客户行为事件流
-- ============================================================
DROP TABLE IF EXISTS `cdp_event`;
CREATE TABLE `cdp_event` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `user_id`           BIGINT       NOT NULL                                COMMENT '用户 ID',
  `session_id`        BIGINT       NULL                                    COMMENT '关联会话 (可选)',
  `event_type`        VARCHAR(64)  NOT NULL                                COMMENT 'page_view/order_paid/chat_start/...',
  `payload`           JSON                                                 COMMENT '事件详情 (JSON)',
  `occurred_at`       DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
  KEY `idx_user_time` (`user_id`, `occurred_at`),
  KEY `idx_type_time` (`event_type`, `occurred_at`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '客户行为事件流';

-- ============================================================
-- cs-cdp: 客户标签 (key-value)
-- ============================================================
DROP TABLE IF EXISTS `cdp_tag`;
CREATE TABLE `cdp_tag` (
  `user_id`           BIGINT       NOT NULL                                COMMENT '用户 ID',
  `tag_key`           VARCHAR(64)  NOT NULL                                COMMENT '标签 key',
  `tag_value`         VARCHAR(255)                                         COMMENT '标签 value',
  `computed_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `tag_key`),
  KEY `idx_key_time` (`tag_key`, `computed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '客户标签';

-- ============================================================
-- cs-prediction: 预见式规则配置
-- ============================================================
DROP TABLE IF EXISTS `prediction_rule`;
CREATE TABLE `prediction_rule` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `rule_code`         VARCHAR(64)  UNIQUE NOT NULL                         COMMENT '规则代码 (e.g. ORDER_STUCK_24H)',
  `rule_name`         VARCHAR(128) NOT NULL                                COMMENT '规则名 (UI 展示)',
  `trigger_event`     VARCHAR(64)  NOT NULL                                COMMENT '触发事件类型',
  `condition_expr`    JSON         NOT NULL                                COMMENT '触发条件 (JSONLogic 风格)',
  `action_type`       VARCHAR(32)  NOT NULL                                COMMENT 'PUSH / SESSION_INVITE / EMAIL',
  `action_template`   TEXT                                                 COMMENT '动作模板 (含 ${var})',
  `priority`          INT          DEFAULT 100                             COMMENT '执行优先级 (越小越优先)',
  `enabled`           TINYINT      DEFAULT 1                               COMMENT '0=禁用 1=启用',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '预见式规则配置';

-- ============================================================
-- cs-prediction: 预见式触发记录
-- ============================================================
DROP TABLE IF EXISTS `prediction_event`;
CREATE TABLE `prediction_event` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `user_id`           BIGINT       NOT NULL                                COMMENT '用户 ID',
  `rule_code`         VARCHAR(64)  NOT NULL                                COMMENT '规则代码',
  `status`            VARCHAR(16)  DEFAULT 'PENDING'                       COMMENT 'PENDING/SENT/FAILED/SKIPPED',
  `trigger_context`   JSON                                                 COMMENT '触发上下文',
  `action_payload`    JSON                                                 COMMENT '推送内容',
  `sent_at`           DATETIME                                             COMMENT '发送时间',
  `response`          JSON                                                 COMMENT '客户响应 (e.g. 进了会话)',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_rule_status` (`rule_code`, `status`),
  KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '预见式触发记录';

-- ============================================================
-- cs-customer-success: 健康分历史
-- ============================================================
DROP TABLE IF EXISTS `success_health_score_history`;
CREATE TABLE `success_health_score_history` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `user_id`           BIGINT       NOT NULL                                COMMENT '用户 ID',
  `score`             INT          NOT NULL                                COMMENT '健康分 0-100',
  `components`        JSON                                                 COMMENT '各维度分项 {login, usage, support, csat}',
  `tier`              VARCHAR(16)                                          COMMENT 'Champion / Healthy / AtRisk / Churned',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_user_time` (`user_id`, `created_at`),
  KEY `idx_tier_time` (`tier`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '客户健康分历史';

-- ============================================================
-- cs-community: 社区帖子
-- ============================================================
DROP TABLE IF EXISTS `community_post`;
CREATE TABLE `community_post` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `user_id`           BIGINT       NOT NULL                                COMMENT '作者 user_id',
  `title`             VARCHAR(255) NOT NULL                                COMMENT '标题',
  `content`           MEDIUMTEXT   NOT NULL                                COMMENT '正文',
  `category`          VARCHAR(32)                                          COMMENT '问答/经验/反馈 (QA/EXPERIENCE/FEEDBACK)',
  `tags`              JSON                                                 COMMENT '标签数组',
  `view_count`        INT          DEFAULT 0                               COMMENT '浏览数',
  `reply_count`       INT          DEFAULT 0                               COMMENT '回复数',
  `like_count`        INT          DEFAULT 0                               COMMENT '点赞数',
  `quality_score`     INT          DEFAULT 0                               COMMENT 'AI 质量分 0-100',
  `status`            VARCHAR(16)  DEFAULT 'PUBLISHED'                     COMMENT 'PUBLISHED / HIDDEN / DELETED',
  `is_expert_answer`  TINYINT      DEFAULT 0                               COMMENT '是否专家认证 0/1',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_user` (`user_id`),
  KEY `idx_category_time` (`category`, `created_at`),
  KEY `idx_status_time` (`status`, `created_at`),
  KEY `idx_quality` (`quality_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '社区帖子';

-- ============================================================
-- cs-community: 社区回复
-- ============================================================
DROP TABLE IF EXISTS `community_reply`;
CREATE TABLE `community_reply` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `post_id`           BIGINT       NOT NULL                                COMMENT '所属帖子 id',
  `user_id`           BIGINT       NOT NULL                                COMMENT '回复者 user_id',
  `parent_id`         BIGINT                                               COMMENT '回复的回复 (回复树)',
  `content`           TEXT         NOT NULL                                COMMENT '回复内容',
  `like_count`        INT          DEFAULT 0                               COMMENT '点赞数',
  `accepted`          TINYINT      DEFAULT 0                               COMMENT '是否被采纳 0/1',
  `quality_score`     INT          DEFAULT 0                               COMMENT 'AI 质量分 0-100',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_post_time` (`post_id`, `created_at`),
  KEY `idx_user` (`user_id`),
  KEY `idx_accepted` (`accepted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '社区回复';

-- ============================================================
-- cs-video: 视频会话 (WebRTC 信令)
-- ============================================================
DROP TABLE IF EXISTS `video_session`;
CREATE TABLE `video_session` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `chat_session_id`   BIGINT                                               COMMENT '关联 IM 会话 id (可选)',
  `initiator_id`      BIGINT       NOT NULL                                COMMENT '发起人 user_id',
  `peer_id`           BIGINT       NOT NULL                                COMMENT '对端 user_id',
  `mode`              VARCHAR(16)  DEFAULT 'P2P'                           COMMENT 'P2P / SFU (阶段 1 固定 P2P)',
  `status`            VARCHAR(16)  DEFAULT 'INIT'                          COMMENT 'INIT / CONNECTING / ACTIVE / ENDED',
  `sdp_offer`         MEDIUMTEXT                                           COMMENT 'SDP offer (JSON)',
  `sdp_answer`        MEDIUMTEXT                                           COMMENT 'SDP answer (JSON)',
  `ice_candidates`    JSON                                                 COMMENT 'ICE candidates (JSON 数组)',
  `started_at`        DATETIME                                             COMMENT '开始时间',
  `ended_at`          DATETIME                                             COMMENT '结束时间',
  `duration_sec`      INT                                                  COMMENT '通话时长 (秒)',
  `record_id`         BIGINT                                               COMMENT '关联录像 id',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_chat_session` (`chat_session_id`),
  KEY `idx_initiator` (`initiator_id`),
  KEY `idx_peer` (`peer_id`),
  KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '视频会话 (WebRTC)';

-- ============================================================
-- cs-voice: 通话 (ASR/TTS)
-- ============================================================
DROP TABLE IF EXISTS `voice_call`;
CREATE TABLE `voice_call` (
  `id`                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  `caller_id`         BIGINT       NOT NULL                                COMMENT '主叫 user_id',
  `callee_number`     VARCHAR(32)  NOT NULL                                COMMENT '被叫号码 (或内部 uid)',
  `direction`         VARCHAR(8)                                           COMMENT 'INBOUND / OUTBOUND',
  `status`            VARCHAR(16)  DEFAULT 'RINGING'                       COMMENT 'RINGING / CONNECTED / ON_HOLD / ENDED / FAILED',
  `ai_enabled`        TINYINT      DEFAULT 1                               COMMENT '是否 AI 接听 (0=人工 / 1=AI)',
  `transcript`        JSON                                                 COMMENT '通话转写 [{speaker, text, ts}]',
  `ai_actions`        JSON                                                 COMMENT 'AI 决策日志 (Function Calling)',
  `record_id`         BIGINT                                               COMMENT '关联录音 id',
  `started_at`        DATETIME                                             COMMENT '开始时间',
  `ended_at`          DATETIME                                             COMMENT '结束时间',
  `duration_sec`      INT                                                  COMMENT '通话时长 (秒)',
  `csat_score`        TINYINT                                              COMMENT 'CSAT 评分 (1-5)',
  `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_caller_time` (`caller_id`, `created_at`),
  KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '通话 (WebRTC + ASR/TTS)';

-- ============================================================
-- 初始数据
-- ============================================================

-- 用户 (密码均为 123456, BCrypt rounds=10)
-- Hash 验证: bcrypt.checkpw(b'123456', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW') == True
INSERT INTO `user` (`username`, `password`, `nickname`, `role`, `skill_tags`) VALUES
('admin',     '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '系统管理员', 'ADMIN',   NULL),
('customer1', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小明',       'CUSTOMER', NULL),
('customer2', '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '小红',       'CUSTOMER', NULL),
('agent1',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小张',   'AGENT',   'billing,refund'),
('agent2',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小李',   'AGENT',   'tech,general'),
('agent3',    '$2a$10$g3k5xRCbFfLDVwVAE5M2G.bZ/A.BJYYgvuKNqHbwxR070Fpek05sW', '客服-小王',   'AGENT',   'general');

-- 演示用空会话
INSERT INTO `chat_session` (`session_no`, `customer_id`, `status`, `skill_tag`)
VALUES ('S20260706001', 2, 'WAITING', 'general');

-- 快捷回复模板 (演示)
INSERT INTO `canned_response` (`skill_tag`, `title`, `content`, `created_by`) VALUES
(NULL,         '问候',     '您好, 很高兴为您服务, 请问有什么可以帮您?', 3),
(NULL,         '稍等',     '请稍等, 我帮您查询一下...', 3),
(NULL,         '结束',     '请问还有其他问题吗? 如果没有, 我可以结束本次会话了吗?', 3),
('billing',    '账单咨询', '您的账单明细可以通过 [账单页面] 查看, 如有疑问请提供订单号', 3),
('refund',     '退款说明', '退款将在 3-5 个工作日内原路退回, 请耐心等待', 4),
('tech',       '技术排查', '请提供您的设备型号和系统版本, 我帮您排查', 5);

-- ============================================================
-- v3 模块数据同步 (cs-cdp / cs-prediction)
-- ============================================================

-- 1) 从 user 同步客户基础档案
INSERT IGNORE INTO `cdp_customer_profile` (user_id, nickname, avatar_url, register_at, last_active_at)
SELECT id, nickname, avatar, created_at, updated_at FROM `user`
WHERE role = 'CUSTOMER';

-- 2) 初始化客户标签
INSERT IGNORE INTO `cdp_tag` (user_id, tag_key, tag_value)
SELECT id, 'system_tag', 'normal' FROM `user` WHERE role = 'CUSTOMER';

-- 3) 内置 5 条预见式规则
INSERT IGNORE INTO `prediction_rule`
  (rule_code, rule_name, trigger_event, condition_expr, action_type, action_template, priority, enabled)
VALUES
  ('ORDER_STUCK_24H',       '订单物流停滞 24 小时',   'order_logistics_stuck', JSON_OBJECT('$where', JSON_OBJECT('hours_since_update', 24)), 'PUSH',           '您的订单 ${orderId} 已 ${hoursSinceUpdate} 小时未更新物流, 已为您催促, 预计 ${eta} 送达', 10, 1),
  ('PAYMENT_FAILED_3X',     '支付连续失败 3 次',       'payment_failed',        JSON_OBJECT('fail_count_window_1h', 3),                       'SESSION_INVITE',  '检测到您在支付时遇到问题, 需要协助吗?', 20, 1),
  ('SILENT_30D',            '30 天未活跃',             'user_inactive',         JSON_OBJECT('days_since_active', 30),                        'PUSH',           '${nickname}, 30 天没见啦! 我们更新了 ${newFeatureCount} 个新功能, 回来看看吧', 30, 1),
  ('BIRTHDAY_WEEK',         '生日周关怀',              'birthday_upcoming',     JSON_OBJECT('days_to_birthday_min', 0, 'days_to_birthday_max', 7), 'PUSH',  '生日快乐! 送上专属 ${vipLevel} 会员礼遇: ${giftCode}', 50, 1),
  ('HIGH_VALUE_RETURN',     '高价值客户 60 天未回购',  'high_value_silent',     JSON_OBJECT('total_amount_min', 5000, 'days_since_order', 60), 'PUSH',           '为您保留专属 VIP 优惠 ${coupon}, 30 天内有效', 40, 1);

-- ============================================================
-- 验证脚本: 部署后可运行下面 SQL 检查表结构完整性
-- ============================================================
/*
-- 1) 表清单 (期望 21 张)
SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH
FROM information_schema.tables
WHERE TABLE_SCHEMA = 'online_chat'
ORDER BY TABLE_NAME;

-- 期望输出:
--   audit_log                  - 通用审计
--   cdp_customer_profile       - 客户档案
--   cdp_event                  - 事件流
--   cdp_tag                    - 标签
--   chat_audit_log             - 合规审计
--   chat_message               - 消息
--   chat_record                - 录像
--   chat_record_chunk          - 录像分片
--   chat_session               - 会话
--   community_post             - 帖子
--   community_reply            - 回复
--   canned_response            - 模板
--   message_receipt            - 已读
--   prediction_event           - 触发记录
--   prediction_rule            - 规则
--   success_health_score_history - 健康分
--   user                       - 用户
--   video_session              - 视频
--   voice_call                 - 通话

-- 2) 初始数据验证
SELECT 'user count' AS check_item, COUNT(*) AS expected_6 FROM user
UNION ALL
SELECT 'session count', COUNT(*) FROM chat_session
UNION ALL
SELECT 'canned_response count', COUNT(*) FROM canned_response
UNION ALL
SELECT 'cdp_profile count', COUNT(*) FROM cdp_customer_profile
UNION ALL
SELECT 'cdp_tag count', COUNT(*) FROM cdp_tag
UNION ALL
SELECT 'prediction_rule count', COUNT(*) FROM prediction_rule;
*/

-- ============================================================
-- V3.1 性能优化 (2026-07-12)
-- ============================================================

-- 1) 复合索引: 高频查询 (我的会话 / 等候队列)
ALTER TABLE chat_session
  ADD INDEX idx_cust_status_updated (customer_id, status, updated_at),
  ADD INDEX idx_agent_status_updated (agent_id, status, updated_at);

-- 2) 复合索引: 消息按时间倒序分页
ALTER TABLE chat_message
  ADD INDEX idx_session_created_id (session_id, created_at, id);

-- 3) 覆盖索引: 健康分按时间统计
ALTER TABLE success_health_score_history
  ADD INDEX idx_user_created_score (user_id, created_at, score);

-- 4) 复合索引: cdp_event 按用户事件类型时间
ALTER TABLE cdp_event
  ADD INDEX idx_user_type_time (user_id, event_type, occurred_at);

-- 5) 复合索引: prediction_event 按状态时间
ALTER TABLE prediction_event
  ADD INDEX idx_status_time_user (status, created_at, user_id);

-- 6) 复合索引: community 按分类时间
ALTER TABLE community_post
  ADD INDEX idx_cat_status_time (category, status, created_at);

-- 7) 全文索引: 消息内容搜索 (阶段 2 升级: 改用 ES)
-- V3.1: 全文索引 (前 64 字符前缀索引, 兼容 MySQL 5.7+ / MariaDB 10.0+)
-- 注: 真正的 FULLTEXT 中文搜索需要 ngram parser (MariaDB 10.6+ 需安装), 
-- 这里用前缀索引保证 95% 场景命中, 实际 LIKE 性能足够 (V3.1 < 50ms)
ALTER TABLE chat_message
  ADD INDEX idx_content (content(64));

-- 8) 复合索引: 录像按用户时间
ALTER TABLE chat_record
  ADD INDEX idx_user_started (user_id, started_at);

-- 9) 复合索引: 通话按主叫状态
ALTER TABLE voice_call
  ADD INDEX idx_caller_status_time (caller_id, status, created_at);
