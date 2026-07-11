# V3 数据库表结构文档

> 更新时间: 2026-07-12
> 数据库: MariaDB 10.11 (utf8mb4)
> 字符集: `SET NAMES utf8mb4` (灌入时显式设置, 防双编码)

---

## 📊 表总览 (13 张)

| # | 表名 | 数据库 | 模块 | 主键 | 关联 | 行数估算 |
|---|------|--------|------|------|------|----------|
| 1 | `user` | cs_auth | cs-auth | id | - | 1000+ |
| 2 | `chat_session` | cs_im | cs-im | id | user, chat_message | 1M+ |
| 3 | `chat_message` | cs_im | cs-im | id | chat_session, user | 100M+ |
| 4 | `message_receipt` | cs_im | cs-im | id | chat_message, user | 100M+ |
| 5 | `canned_response` | cs_im | cs-im | id | user | 1k+ |
| 6 | `audit_log` | cs_im | cs-im | id | user | 10M+ |
| 7 | `chat_record` | cs_im | cs-im | id | chat_session | 1M+ |
| 8 | `chat_record_chunk` | cs_im | cs-im | id | chat_record | 10M+ |
| 9 | `chat_audit_log` | cs_im | cs-im | id | user | 10M+ |
| 10 | `cdp_customer_profile` | cs_cdp | cs-cdp | user_id | (与 user 1:1) | 1000+ |
| 11 | `cdp_event` | cs_cdp | cs-cdp | id | user | 100M+ |
| 12 | `cdp_tag` | cs_cdp | cs-cdp | (user_id, tag_key) | user | 10k+ |
| 13 | `community_post` | cs_community | cs-community | id | user | 10k+ |
| 14 | `community_reply` | cs_community | cs-community | id | community_post, user | 100k+ |
| 15 | `prediction_rule` | cs_pred | cs-prediction | id | - | 50+ |
| 16 | `prediction_event` | cs_pred | cs-prediction | id | prediction_rule, user | 1M+ |
| 19 | `health_score_history` | cs_success | cs-customer-success | id | user | 100k+ |

**总 19 张表, cs-auth / cs-im / cs-cdp / cs-community / cs-prediction / cs-customer-success / cs-video / cs-voice (8 个模块).**

---

## 🔧 cs_im (9 张)

### 1. user (用户表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | 用户 ID |
| `username` | VARCHAR(64) | NOT NULL, UNIQUE | - | 登录名 |
| `password` | VARCHAR(128) | NOT NULL | - | BCrypt 加密密码 |
| `nickname` | VARCHAR(64) | NOT NULL | - | 显示昵称 |
| `role` | VARCHAR(16) | NOT NULL | 'CUSTOMER' | CUSTOMER / AGENT / ADMIN |
| `skill_tags` | VARCHAR(255) | - | NULL | 坐席技能 (逗号或 JSON) |
| `avatar` | VARCHAR(255) | - | NULL | 头像 URL |
| `status` | TINYINT | NOT NULL | 1 | 1=启用 0=禁用 |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |
| `updated_at` | DATETIME | NOT NULL | ON UPDATE | 自动更新 |

**索引**: PRIMARY (id), UNIQUE (username), INDEX (role, status)

### 2. chat_session (会话表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | 会话 ID |
| `session_no` | VARCHAR(32) | NOT NULL, UNIQUE | - | 业务号 (S + 时间戳) |
| `customer_id` | BIGINT | NOT NULL, FK | - | 客户 ID |
| `agent_id` | BIGINT | FK | NULL | 坐席 ID (WAITING 时为 NULL) |
| `skill_tag` | VARCHAR(32) | - | NULL | 技能 (general/billing/refund/tech) |
| `status` | VARCHAR(16) | NOT NULL | 'WAITING' | WAITING / ACTIVE / CLOSED |
| `is_bot` | TINYINT | NOT NULL | 0 | 0=人工 1=智能客服 |
| `transferred_from_agent_id` | BIGINT | FK | NULL | 转接前的坐席 |
| `transfer_reason` | VARCHAR(500) | - | NULL | - |
| `last_message` | VARCHAR(500) | - | NULL | 缓存最后一条 (列表展示优化) |
| `rating` | TINYINT | - | NULL | CSAT 1-5 |
| `rating_comment` | VARCHAR(500) | - | NULL | - |
| `rated_at` | DATETIME | - | NULL | 评分时间 |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |
| `updated_at` | DATETIME | NOT NULL | ON UPDATE | - |
| `closed_at` | DATETIME | - | NULL | 关闭时间 |

**索引**: PRIMARY (id), UNIQUE (session_no), INDEX (customer_id), INDEX (agent_id, status), INDEX (status, created_at)

**关键场景**:
- `CAS 防串线`: `UPDATE chat_session SET agent_id=X, status='ACTIVE' WHERE id=? AND status='WAITING' AND agent_id IS NULL`
- `转人工`: 关闭 bot 会话, 创建新 human 会话

### 3. chat_message (消息表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `session_id` | BIGINT | NOT NULL, FK | - | 会话 ID |
| `sender_id` | BIGINT | NOT NULL | - | 发送者 ID |
| `sender_role` | VARCHAR(16) | NOT NULL | - | CUSTOMER / AGENT / SYSTEM |
| `msg_type` | VARCHAR(16) | NOT NULL | 'TEXT' | TEXT / IMAGE / FILE / SYSTEM / RECALL |
| `content` | TEXT | NOT NULL | - | 文本内容 或 URL |
| `recalled` | TINYINT | NOT NULL | 0 | 0=正常 1=已撤回 |
| `created_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | 毫秒精度 |

**索引**: PRIMARY (id), INDEX (session_id, created_at), INDEX (sender_id)

**关键算法**:
- `search`: `SELECT * FROM chat_message WHERE session_id=? AND content LIKE ? ORDER BY created_at DESC LIMIT 50`
- `unread count`: `SELECT COUNT(*) FROM chat_message WHERE session_id=? AND sender_id != ? AND id NOT IN (SELECT message_id FROM message_receipt WHERE user_id=?)`

### 4. message_receipt (消息回执表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `message_id` | BIGINT | NOT NULL, FK | - | - |
| `user_id` | BIGINT | NOT NULL, FK | - | 已读用户 |
| `read_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

**索引**: PRIMARY (id), UNIQUE (message_id, user_id), INDEX (user_id)

### 5. canned_response (快捷回复模板)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `skill_tag` | VARCHAR(32) | - | NULL | 按技能分类 (NULL=通用) |
| `title` | VARCHAR(64) | NOT NULL | - | 模板标题 |
| `content` | TEXT | NOT NULL | - | 模板内容 |
| `created_by` | BIGINT | NOT NULL, FK | - | 创建坐席 ID |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |

### 6. audit_log (业务审计日志)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `user_id` | BIGINT | FK | NULL | 操作人 |
| `action` | VARCHAR(32) | NOT NULL | - | LOGIN/CREATE_SESSION/CLAIM/TRANSFER/CLOSE/RATE/RECALL/... |
| `target` | VARCHAR(64) | - | NULL | 操作目标 (如 sessionId) |
| `detail` | VARCHAR(500) | - | NULL | - |
| `ip` | VARCHAR(64) | - | NULL | - |
| `created_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

**索引**: PRIMARY (id), INDEX (user_id, created_at), INDEX (action, created_at)

### 7. chat_record (录像主表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `session_id` | BIGINT | NOT NULL, FK | - | 所属会话 |
| `user_id` | BIGINT | NOT NULL, FK | - | 被录制方 |
| `user_role` | VARCHAR(16) | NOT NULL | - | CUSTOMER / AGENT |
| `started_at` | DATETIME(3) | NOT NULL | - | 开始时间 |
| `ended_at` | DATETIME(3) | - | NULL | 结束时间 |
| `end_reason` | VARCHAR(32) | - | NULL | NORMAL/USER_STOP/PAGE_CLOSE/PROCESS_KILLED/ERROR/SESSION_CLOSED |
| `chunk_count` | INT | NOT NULL | 0 | 已上传分片数 |
| `total_bytes` | BIGINT | NOT NULL | 0 | 累计大小 (字节) |
| `consent_given` | TINYINT(1) | NOT NULL | 0 | 合规: 是否获得用户同意 (0=否 1=是) |
| `created_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

### 8. chat_record_chunk (录像分片表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `record_id` | BIGINT | NOT NULL, FK | - | 所属录像 |
| `sequence_no` | INT | NOT NULL | - | 分片序号 (从 0 升序) |
| `mime_type` | VARCHAR(64) | NOT NULL | 'video/webm' | - |
| `duration_ms` | INT | NOT NULL | 0 | 该分片时长 |
| `byte_size` | INT | NOT NULL | 0 | 该分片大小 |
| `storage_path` | VARCHAR(255) | NOT NULL | - | 落盘路径 `<root>/<recordId>/<seq>-<uuid>.webm` |
| `uploaded_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

**外键**: `fk_chunk_record` ON DELETE CASCADE

**分片上传协议**:
1. `POST /api/im/record/init` → 拿 recordId
2. `POST /api/im/record/chunk` (multipart, sequenceNo, chunk) → 循环上传
3. `POST /api/im/record/end` (endReason) → 收尾

### 9. chat_audit_log (录像合规审计)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `actor_id` | BIGINT | NOT NULL | - | 操作人 |
| `actor_role` | VARCHAR(16) | NOT NULL | - | CUSTOMER/AGENT/ADMIN/SYSTEM |
| `action` | VARCHAR(64) | NOT NULL | - | RECORD_INIT/END/DENY_NO_CONSENT/FORBIDDEN/... |
| `target` | VARCHAR(128) | - | NULL | 操作目标 (sessionId/recordId) |
| `detail` | TEXT | - | NULL | 明细 |
| `ip` | VARCHAR(45) | - | NULL | IPv4/IPv6 |
| `user_agent` | VARCHAR(255) | - | NULL | 浏览器 UA |
| `created_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

---

## 🔧 cs_cdp (3 张)

### 10. cdp_customer_profile (客户 360 画像)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `user_id` | BIGINT | PK | - | 1:1 with user.id |
| `nickname` | VARCHAR(64) | - | NULL | 冗余 (避免 join) |
| `avatar_url` | VARCHAR(255) | - | NULL | - |
| `vip_level` | INT | - | 0 | 0=普通 1=银 2=金 3=钻 |
| `register_at` | DATETIME | - | NULL | - |
| `last_active_at` | DATETIME | - | NULL | 最近活跃 |
| `total_orders` | INT | - | 0 | 累计订单 |
| `total_amount` | DECIMAL(15,2) | - | 0 | 累计消费 |
| `avg_csat` | DECIMAL(3,2) | - | NULL | 平均满意度 |
| `total_sessions` | INT | - | 0 | 累计会话 |
| `churn_risk` | INT | - | 0 | 流失风险 0-100 |
| `health_score` | INT | - | NULL | 健康分 (从 cs-success 同步) |
| `tags` | VARCHAR(500) | - | NULL | 标签 JSON 数组 |
| `preferences` | VARCHAR(500) | - | NULL | 偏好 JSON |

**关键算法**:
- `360 画像聚合`: `ProfileService.getProfile(uid)` 6 步聚合
  1. user 表基础信息
  2. cdp_tag 标签 (空时触发计算)
  3. 活跃度 (最近 30 天 cdp_event 计数)
  4. 健康分 (调 cs-success)
  5. RFM (Recency/Frequency/Monetary)
  6. 合并输出

### 11. cdp_event (埋点事件表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `user_id` | BIGINT | NOT NULL, FK | - | - |
| `event_type` | VARCHAR(64) | NOT NULL | - | 事件类型 (ACTIVE/CLICK/SCROLL/...) |
| `payload` | TEXT | - | NULL | JSON 载荷 |
| `session_id` | BIGINT | FK | NULL | 关联会话 |
| `occurred_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

**索引**: PRIMARY (id), INDEX (user_id, occurred_at), INDEX (event_type, occurred_at)

**用途**:
- 活跃度计算
- 行为分析
- AI 训练数据

### 12. cdp_tag (客户标签表)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `user_id` | BIGINT | PK (联合) | - | - |
| `tag_key` | VARCHAR(64) | PK (联合) | - | 标签键 (高频购买/潜在流失/...) |
| `tag_value` | VARCHAR(255) | - | NULL | 标签值 |
| `confidence` | DECIMAL(3,2) | - | 1.0 | 置信度 0-1 |
| `computed_at` | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | - |

**索引**: PRIMARY (user_id, tag_key), INDEX (tag_key)

**计算触发**:
- 首次访问 ProfileService (空时)
- 定期批量重算 (阶段 2 调度任务)
- 主动 recompute (`POST /api/cdp/recompute`)

---

## 🔧 cs_community (2 张)

### 13. community_post (社区帖子)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `user_id` | BIGINT | NOT NULL, FK | - | 发帖人 |
| `title` | VARCHAR(255) | NOT NULL | - | - |
| `content` | TEXT | NOT NULL | - | - |
| `category` | VARCHAR(32) | NOT NULL | 'QA' | QA/EXPERIENCE/FEEDBACK |
| `like_count` | INT | NOT NULL | 0 | 缓存 (避免 COUNT) |
| `reply_count` | INT | NOT NULL | 0 | 缓存 |
| `view_count` | INT | NOT NULL | 0 | - |
| `is_pinned` | TINYINT | - | 0 | 0=普通 1=置顶 |
| `is_locked` | TINYINT | - | 0 | 0=开 1=锁 (不能回复) |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |
| `updated_at` | DATETIME | NOT NULL | ON UPDATE | - |

### 14. community_reply (社区回复)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `post_id` | BIGINT | NOT NULL, FK | - | 所属帖子 |
| `user_id` | BIGINT | NOT NULL, FK | - | 回复人 |
| `content` | TEXT | NOT NULL | - | - |
| `parent_id` | BIGINT | FK | NULL | 父回复 ID (嵌套) |
| `accepted` | TINYINT | NOT NULL | 0 | 0=否 1=接受为最佳答案 (仅楼主) |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |

**索引**: PRIMARY (id), INDEX (post_id, created_at), INDEX (parent_id)

**接受答案流程**:
1. 楼主调用 `POST /api/community/replies/{id}/accept`
2. 后端校验: 当前用户 = 帖子作者
3. UPDATE reply SET accepted=1
4. UPDATE post (可选: 加 accepted_reply_id 字段)

---

## 🔧 cs_pred (2 张)

### 15. prediction_rule (预测规则)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `name` | VARCHAR(128) | NOT NULL | - | 规则名 |
| `description` | VARCHAR(500) | - | NULL | - |
| `trigger_event` | VARCHAR(64) | NOT NULL | - | 触发事件 (LOGIN/CHAT_END/...) |
| `condition_expr` | TEXT | NOT NULL | - | 条件表达式 (SpEL) |
| `action_type` | VARCHAR(32) | NOT NULL | - | OFFER/ALERT/RECOMMEND/... |
| `action_template` | TEXT | - | NULL | 动作模板 |
| `enabled` | TINYINT | NOT NULL | 1 | 0=禁用 1=启用 |
| `priority` | INT | NOT NULL | 100 | 优先级 (越大越先) |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |
| `updated_at` | DATETIME | NOT NULL | ON UPDATE | - |

**示例规则**:
```yaml
- name: "高价值客户久未登录"
  trigger: "HEARTBEAT"
  condition: "profile.vipLevel >= 2 && daysSinceLastActive > 7"
  action: "OFFER: 专属客服 + 优惠券"
```

### 16. prediction_event (预测触发历史)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `rule_id` | BIGINT | NOT NULL, FK | - | 触发的规则 |
| `user_id` | BIGINT | NOT NULL, FK | - | 目标用户 |
| `action` | VARCHAR(32) | NOT NULL | - | 触发的动作 |
| `reason` | VARCHAR(500) | - | NULL | 触发原因 |
| `delivered` | TINYINT | NOT NULL | 0 | 0=未推送 1=已推送 |
| `delivered_at` | DATETIME | - | NULL | - |
| `created_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |

**索引**: PRIMARY (id), INDEX (user_id, created_at), INDEX (rule_id)

---

## 🔧 cs_success (1 张)

### 19. health_score_history (健康分历史)

| 字段 | 类型 | 约束 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | BIGINT | PK, AUTO_INCREMENT | - | - |
| `user_id` | BIGINT | NOT NULL | - | - |
| `score` | INT | NOT NULL | - | 0-100 |
| `level` | VARCHAR(16) | NOT NULL | - | HEALTHY/NORMAL/RISK/CHURN_RISK |
| `active_days` | INT | - | NULL | 输入参数 |
| `avg_csat` | DECIMAL(3,2) | - | NULL | 输入参数 |
| `risk_events` | INT | - | 0 | 风险事件数 |
| `computed_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | - |

**健康分公式**:
```java
score = clamp(
  base_score
  + 0.4 * log(activeDays + 1) * 10
  + 0.4 * (avgCsat - 3) * 20
  - 0.2 * riskEvents
, 0, 100)

// 等级
if (score >= 80) HEALTHY
else if (score >= 60) NORMAL
else if (score >= 40) RISK
else CHURN_RISK
```

---

## 🔗 跨服务数据流

### 用户生命周期
```
user (cs_auth)
  ↓ 1:1
cdp_customer_profile (cs_cdp)
  ↓ 1:N
cdp_event (cs_cdp)
  ↓ 1:N
cdp_tag (cs_cdp)
  ↓ 同步
health_score_history (cs_success)

user (cs_auth)
  ↓ 1:N
chat_session (cs_im)
  ↓ 1:N
chat_message (cs_im)
  ↓ 1:N
message_receipt (cs_im)

chat_session
  ↓ 1:N
chat_record (cs_im)
  ↓ 1:N
chat_record_chunk (cs_im)

user (cs_auth)
  ↓ 1:N
community_post (cs_community)
  ↓ 1:N
community_reply (cs_community)

user (cs_auth)
  ↓ 触发
prediction_event (cs_pred)
  ↓ N:1
prediction_rule (cs_pred)
```

### 实时数据流 (STOMP 推送)
```
客户/坐席操作
  ↓
SessionService.create/close/claim/rate/transfer
  ↓
WsPushService.broadcastRealtime(event, data)
  ↓ 2 路
  ├─ STOMP /topic/realtime → 大屏 RealtimeMonitor
  └─ STOMP /user/queue/{uid} → 个人客户端
```

---

## 📈 性能优化建议

### 索引
- `chat_message(session_id, created_at)` - 拉历史
- `chat_message(sender_id, created_at)` - 用户历史
- `chat_session(agent_id, status)` - 坐席列表
- `chat_session(status, created_at)` - 等候队列
- `cdp_event(user_id, occurred_at)` - 行为分析

### 分区
- `chat_message` 按月分区 (大表, 100M+ 行)
- `chat_record_chunk` 按 record_id HASH
- `cdp_event` 按月分区

### 归档
- 6 个月前的 chat_message 归档到冷库
- 1 年前的 chat_record 压缩存储

### 读写分离
- 主库 (写) + 从库 (读, 报表)
- MyBatis-Plus `@DS` 注解支持

### 缓存
- Redis: chat_session (5min) / cdp_profile (1h) / canned_response (1d)
- Caffeine: 健康分计算 (10min)

---

## 🔒 安全合规

### 密码
- BCrypt (10 轮) 加密存储
- 不能明文查 (永远走 passwordEncoder.matches)

### PII 脱敏
- `@Desensitize(MOBILE)` → 138****5678
- `@Desensitize(EMAIL)` → a***@example.com
- `@Desensitize(ID_CARD)` → 1101***********1234
- `@Desensitize(NAME)` → 张*
- `@Desensitize(BANK_CARD)` → 6222 **** **** 1234

### 录像合规
- `consent_given` 必须为 1 才能录像
- 客户前端必须弹窗征求同意
- `chat_audit_log` 记录所有操作 (含 IP + UA)

### 限流
- `@RateLimit(key="login", permits=5, window=60)` 防爆破
- Redis 滑动窗口

---

## 🛠️ 维护命令

### 备份
```bash
mysqldump -u root -p --single-transaction --routines cs_im > backup_cs_im_$(date +%Y%m%d).sql
mysqldump -u root -p --no-data --routines cs_im > schema_cs_im.sql
```

### 灌入 schema
```bash
mysql -u root -p < sql/schema.sql
# 注意: 灌入时必须显式
mysql -u root -p -e "SET NAMES utf8mb4" cs_im < schema.sql
```

### 监控
```sql
-- 慢查询
SHOW FULL PROCESSLIST;

-- 表大小
SELECT table_name, data_length/1024/1024 AS size_mb
FROM information_schema.tables
WHERE table_schema = 'cs_im'
ORDER BY data_length DESC;

-- 索引使用
EXPLAIN SELECT * FROM chat_message WHERE session_id=100;
```

---

## 📋 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v3.0.0 | 2026-07-12 | 19 张表完整 (新增 cdp_*, community_*, prediction_*, health_score_history) |
| v2.5.0 | 2025-12 | 9 张表 (cs-im 全部) |
| v2.0.0 | 2025-08 | + chat_record, chat_record_chunk (录像) |
| v1.0.0 | 2025-01 | user + chat_session + chat_message 基础 |

---

# 附录: V6 增强 (2026-07-12)

## A. 客户端索引 (V6)

为支持实时大屏 / AI 自研 / 限流 / 脱敏, 新增 8 张 V3 新增 (8 个模块, 已包含在 19 张内).

### A.1 cdp_event 索引优化
- 主键: id
- 二级: (uid, created_at) 用于用户行为时间线
- 二级: (event_type, created_at) 用于事件类型分析
- 三级: (session_id) 用于会话回溯

### A.2 cdp_tag 索引
- 主键: id
- 二级: (tag_code) UNIQUE 用于代码查重
- 二级: (category, is_active) 用于按分类列出活跃标签

### A.3 cdp_customer_profile 索引
- 主键: uid (UNIQUE)
- 二级: (vip_level, updated_at) 用于 VIP 客户查询
- 三级: (last_active_at) 用于流失客户识别 (30 天未活跃)

## B. 19 张表速查表

| 库 | 表名 | 用途 | 行数级 |
|---|------|------|--------|
| cs_auth | user | 鉴权 + 角色 | 1000+ |
| cs_im | chat_session | 会话核心 | 100K+ |
| cs_im | chat_message | 消息 | 1M+ |
| cs_im | message_receipt | 已读回执 | 100K+ |
| cs_im | canned_response | 模板回复 | 100+ |
| cs_im | file_storage | 文件 | 10K+ |
| cs_im | audit_log | 审计日志 | 100K+ |
| cs_im | chat_record | 录像 | 1K+ |
| cs_cdp | cdp_event | 事件流 | 1M+ |
| cs_cdp | cdp_tag | 标签 | 50+ |
| cs_cdp | cdp_customer_profile | 用户画像 | 1000+ |
| cs_community | community_post | 帖子 | 100+ |
| cs_community | community_reply | 回复 | 1K+ |
| cs_pred | prediction_rule | 规则 | 20+ |
| cs_pred | prediction_event | 触发事件 | 10K+ |
| cs_success | health_score_history | 健康分 | 10K+ |
| (cs_im) | user | 共用 |

## C. 容量规划

按 1000 客户 + 50 坐席 + 1 万会话/天:

- 每天新增 chat_message: ~50 万 (平均 50 条/会话)
- 每天新增 cdp_event: ~100 万 (10 事件/客户/天)
- 每天新增 chat_record: ~500 (录像率 5%)
- 数据库年增长: ~200 GB
- 推荐磁盘: 1 TB SSD + 备份到 OSS

## D. 备份策略

- 每日 0 点全量备份 (mysqldump)
- 每周日 0 点完整备份 + 7 天保留
- 每月归档到 OSS cold storage
- RPO (数据丢失容忍): 1 天
- RTO (恢复时间目标): 4 小时

