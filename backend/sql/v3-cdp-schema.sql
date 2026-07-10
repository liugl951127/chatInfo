-- V3 数字孪生 360 + 预见式服务 + 客户成功 + 社区 + 视频/语音 schema
-- 2026-07-10  v3 架构
-- 必须先 SET NAMES utf8mb4 避免双编码 (参考 v2 fix)

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- ===========================================
-- cs-cdp: 数字孪生 360
-- ===========================================

-- 客户主档案 (1:1 with user)
CREATE TABLE IF NOT EXISTS cdp_customer_profile (
    user_id           BIGINT       PRIMARY KEY,
    nickname          VARCHAR(64),
    avatar_url        VARCHAR(255),
    vip_level         TINYINT      DEFAULT 0 COMMENT '0=普通 1=银 2=金 3=钻石',
    register_at       DATETIME     COMMENT '注册时间',
    last_active_at    DATETIME     COMMENT '最后活跃时间',
    total_orders      INT          DEFAULT 0 COMMENT '历史订单数',
    total_amount      DECIMAL(12,2) DEFAULT 0 COMMENT '历史消费金额',
    avg_csat          DECIMAL(2,1) DEFAULT 0 COMMENT '平均满意度 (0-5)',
    total_sessions    INT          DEFAULT 0 COMMENT '历史会话数',
    churn_risk        TINYINT      DEFAULT 0 COMMENT '流失风险 0=健康 1=关注 2=风险 3=流失',
    health_score      INT          DEFAULT 100 COMMENT '健康分 0-100',
    tags              JSON         COMMENT '标签数组',
    preferences       JSON         COMMENT '偏好 (时间/渠道/语言)',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '客户主档案';

-- 事件流 (append-only)
CREATE TABLE IF NOT EXISTS cdp_event (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    session_id        BIGINT       COMMENT '关联会话 (可选)',
    event_type        VARCHAR(64)  NOT NULL COMMENT 'page_view/order_paid/chat_start/...',
    payload           JSON         COMMENT '事件详情',
    occurred_at       DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user_time (user_id, occurred_at),
    INDEX idx_type_time (event_type, occurred_at),
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '客户行为事件流';

-- 标签计算结果
CREATE TABLE IF NOT EXISTS cdp_tag (
    user_id           BIGINT       NOT NULL,
    tag_key           VARCHAR(64)  NOT NULL,
    tag_value         VARCHAR(255),
    computed_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, tag_key),
    INDEX idx_key_time (tag_key, computed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '客户标签';

-- ===========================================
-- cs-prediction: 预见式服务
-- ===========================================

-- 规则配置
CREATE TABLE IF NOT EXISTS prediction_rule (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    rule_code         VARCHAR(64)  UNIQUE NOT NULL COMMENT '规则代码 (e.g. ORDER_STUCK_24H)',
    rule_name         VARCHAR(128) NOT NULL COMMENT '规则名 (UI 展示)',
    trigger_event     VARCHAR(64)  NOT NULL COMMENT '触发事件类型',
    condition_expr    JSON         NOT NULL COMMENT '触发条件 (JSONLogic)',
    action_type       VARCHAR(32)  NOT NULL COMMENT 'PUSH/SESSION_INVITE/EMAIL',
    action_template   TEXT         COMMENT '动作模板 (含 ${var})',
    priority          INT          DEFAULT 100 COMMENT '执行优先级',
    enabled           TINYINT      DEFAULT 1,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '预见式规则配置';

-- 触发记录
CREATE TABLE IF NOT EXISTS prediction_event (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    rule_code         VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/SKIPPED',
    trigger_context   JSON         COMMENT '触发上下文',
    action_payload    JSON         COMMENT '推送内容',
    sent_at           DATETIME,
    response          JSON         COMMENT '客户响应 (e.g. 进了会话)',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, created_at),
    INDEX idx_rule_status (rule_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '预见式触发记录';

-- 内置规则数据
INSERT IGNORE INTO prediction_rule (rule_code, rule_name, trigger_event, condition_expr, action_type, action_template, priority) VALUES
('ORDER_STUCK_24H', '订单物流停滞 24 小时', 'order_logistics_stuck', '{"hours_since_update": {"$gte": 24}}', 'PUSH', '您的订单 ${orderId} 已 ${hoursSinceUpdate} 小时未更新物流, 已为您催促, 预计 ${eta} 送达', 10),
('PAYMENT_FAILED_3X', '支付连续失败 3 次', 'payment_failed', '{"fail_count_window_1h": {"$gte": 3}}', 'SESSION_INVITE', '检测到您在支付时遇到问题, 需要协助吗?', 20),
('SILENT_30D', '30 天未活跃', 'user_inactive', '{"days_since_active": {"$gte": 30}}', 'PUSH', '${nickname}, 30 天没见啦! 我们更新了 ${newFeatureCount} 个新功能, 回来看看吧', 30),
('BIRTHDAY_WEEK', '生日周关怀', 'birthday_upcoming', '{"days_to_birthday": {"$lte": 7, "$gte": 0}}', 'PUSH', '生日快乐! 送上专属 ${vipLevel} 会员礼遇: ${giftCode}', 50),
('HIGH_VALUE_RETURN', '高价值客户 60 天未回购', 'high_value_silent', '{"total_amount": {"$gte": 5000}, "days_since_order": {"$gte": 60}}', 'PUSH', '为您保留专属 VIP 优惠 ${coupon}, 30 天内有效', 40);

-- 主动消息记录
CREATE TABLE IF NOT EXISTS proactive_message (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    channel           VARCHAR(16)  COMMENT 'STOMP/SMS/PUSH/EMAIL',
    payload           JSON         NOT NULL,
    related_session_id BIGINT,
    read_at           DATETIME,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '主动消息';

-- ===========================================
-- cs-customer-success: 客户成功
-- ===========================================

CREATE TABLE IF NOT EXISTS success_health_score_history (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    score             INT          NOT NULL,
    components        JSON         COMMENT '各维度分项',
    tier              VARCHAR(16)  COMMENT 'Champion/Healthy/AtRisk/Churned',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '健康分历史';

-- ===========================================
-- cs-community: 群体智能
-- ===========================================

CREATE TABLE IF NOT EXISTS community_post (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(255) NOT NULL,
    content           MEDIUMTEXT   NOT NULL,
    category          VARCHAR(32)  COMMENT '问答/经验/反馈',
    tags              JSON         COMMENT '标签数组',
    view_count        INT          DEFAULT 0,
    reply_count       INT          DEFAULT 0,
    like_count        INT          DEFAULT 0,
    quality_score     INT          DEFAULT 0 COMMENT 'AI 质量分 0-100',
    status            VARCHAR(16)  DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/HIDDEN/DELETED',
    is_expert_answer  TINYINT      DEFAULT 0 COMMENT '是否专家认证',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_category_time (category, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '社区帖子';

CREATE TABLE IF NOT EXISTS community_reply (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    post_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    parent_id         BIGINT       COMMENT '回复的回复',
    content           TEXT         NOT NULL,
    like_count        INT          DEFAULT 0,
    accepted          TINYINT      DEFAULT 0 COMMENT '是否被采纳',
    quality_score     INT          DEFAULT 0,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_post (post_id, created_at),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '社区回复';

CREATE TABLE IF NOT EXISTS community_user_stats (
    user_id           BIGINT       PRIMARY KEY,
    post_count        INT          DEFAULT 0,
    reply_count       INT          DEFAULT 0,
    accepted_count    INT          DEFAULT 0,
    points            INT          DEFAULT 0,
    level             VARCHAR(16)  DEFAULT 'NEWBIE' COMMENT 'NEWBIE/REGULAR/EXPERT/KOL',
    badges            JSON         COMMENT '勋章数组',
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '社区用户统计';

-- ===========================================
-- cs-video: 视频会话
-- ===========================================

CREATE TABLE IF NOT EXISTS video_session (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    chat_session_id   BIGINT       COMMENT '关联 IM 会话',
    initiator_id      BIGINT       NOT NULL,
    peer_id           BIGINT       NOT NULL,
    mode              VARCHAR(16)  DEFAULT 'P2P' COMMENT 'P2P/SFU',
    status            VARCHAR(16)  DEFAULT 'INIT' COMMENT 'INIT/CONNECTING/ACTIVE/ENDED',
    sdp_offer         MEDIUMTEXT,
    sdp_answer        MEDIUMTEXT,
    ice_candidates    JSON,
    started_at        DATETIME,
    ended_at          DATETIME,
    duration_sec      INT,
    record_id         BIGINT       COMMENT '关联录像',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (chat_session_id),
    INDEX idx_initiator (initiator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '视频会话';

-- ===========================================
-- cs-voice: 智能电话
-- ===========================================

CREATE TABLE IF NOT EXISTS voice_call (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    caller_id         BIGINT       NOT NULL,
    callee_number     VARCHAR(32)  NOT NULL COMMENT '被叫号码 (或内部 uid)',
    direction         VARCHAR(8)   COMMENT 'INBOUND/OUTBOUND',
    status            VARCHAR(16)  DEFAULT 'RINGING' COMMENT 'RINGING/CONNECTED/ENDED/FAILED',
    ai_enabled        TINYINT      DEFAULT 1 COMMENT '是否 AI 接听',
    transcript        JSON         COMMENT '通话转写 [{speaker, text, ts}]',
    ai_actions        JSON         COMMENT 'AI 决策日志 (Function Calling)',
    record_id         BIGINT       COMMENT '关联录音',
    started_at        DATETIME,
    ended_at          DATETIME,
    duration_sec      INT,
    csat_score        TINYINT,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_caller (caller_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '通话';

-- ===========================================
-- cs-cdp: 初始化 (从 user 表同步基础数据)
-- ===========================================

INSERT IGNORE INTO cdp_customer_profile (user_id, nickname, avatar_url, register_at, last_active_at)
SELECT id, nickname, avatar_url, created_at, updated_at FROM user
WHERE id IS NOT NULL;

-- 给所有已有用户一个初始空标签
INSERT IGNORE INTO cdp_tag (user_id, tag_key, tag_value)
SELECT id, 'new_customer', 'true' FROM user WHERE id IS NOT NULL;