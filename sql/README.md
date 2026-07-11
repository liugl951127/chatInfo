# SQL 脚本说明

> V3.0 数据库初始化

---

## 文件清单

| 文件 | 大小 | 用途 |
|------|------|------|
| **v3-full-schema.sql** | 32 KB | **唯一主脚本** — 19 张表 + 51 索引 + 字段注释 + 初始数据 |

> 历史文件 `schema.sql` (cs-im/user) 和 `backend/sql/v3-cdp-schema.sql` (cs-cdp/community/prediction/video/voice) 已合并到 v3-full-schema.sql.

---

## 部署方式

### Docker 自动初始化 (推荐)

`docker-compose.yml` 挂载 `./sql` 到 mariadb 容器 `/docker-entrypoint-initdb.d`:

```yaml
volumes:
  - ./sql:/docker-entrypoint-initdb.d:ro
```

容器启动时, mariadb 会按字母顺序执行所有 `.sql` 文件. **V3.0 只有 1 个 SQL 文件**, 顺序无关.

### 手动初始化

```bash
# 1. 创建库
mariadb -u root -p -e "CREATE DATABASE IF NOT EXISTS online_chat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 执行脚本
mariadb -u root -p online_chat < sql/v3-full-schema.sql

# 3. 验证 (期望 19 张表)
mariadb -u root -p -e "USE online_chat; SHOW TABLES;"
```

### 增量更新 (开发期)

脚本使用 `DROP TABLE IF EXISTS`, **会清空所有数据**. 生产环境请手动备份:

```bash
# 备份
mysqldump -u root -p online_chat > backup_$(date +%Y%m%d).sql

# 重建 (仅开发)
mariadb -u root -p online_chat < sql/v3-full-schema.sql
```

生产环境请使用迁移工具 (Flyway / Liquibase), 不在 V3 范围内.

---

## 表结构 (19 张)

### cs-auth (1 张)
- `user` - 用户表 (CUSTOMER/AGENT/ADMIN)

### cs-im (8 张)
- `chat_session` - 会话
- `chat_message` - 消息
- `message_receipt` - 已读回执
- `canned_response` - 快捷回复模板
- `audit_log` - 通用审计
- `chat_record` - 录像主表 (合规)
- `chat_record_chunk` - 录像分片
- `chat_audit_log` - 合规审计 (录制/转接/退出)

### cs-cdp (3 张)
- `cdp_event` - 行为事件流
- `cdp_tag` - 客户标签 (key-value)
- `cdp_customer_profile` - 客户主档案 (1:1 with user)

### cs-community (2 张)
- `community_post` - 帖子
- `community_reply` - 回复

### cs-prediction (2 张)
- `prediction_rule` - 规则配置
- `prediction_event` - 触发记录

### cs-customer-success (1 张)
- `success_health_score_history` - 健康分历史

### cs-video (1 张)
- `video_session` - 视频会话 (WebRTC)

### cs-voice (1 张)
- `voice_call` - 通话 (ASR/TTS)

**总计 19 张表, 51 索引, 159 字段注释**

---

## 初始数据

- **6 个用户**: 1 admin + 3 customer + 3 agent (BCrypt 密码 `123456`)
- **1 个空会话**: S20260706001 (WAITING)
- **6 个模板**: 3 通用 + 1 billing + 1 refund + 1 tech
- **CDP 同步**: user → cdp_customer_profile (自动)
- **5 条规则**: 订单停滞/支付失败/30天未活/生日周/高价值回购

---

## 字符集

- 数据库: `utf8mb4` / `utf8mb4_unicode_ci`
- 文件: UTF-8 (无 BOM)
- 导入: 必须 `SET NAMES utf8mb4` (脚本头部已有)

**注意**: 直接 `mariadb < file.sql` 客户端默认 latin1, 会导致中文双编码. 修复:
```sql
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
```

---

## 验证

部署完成后, 运行脚本末尾的验证 SQL:

```sql
-- 1) 表清单 (期望 19 张)
SELECT TABLE_NAME FROM information_schema.tables
WHERE TABLE_SCHEMA = 'online_chat' ORDER BY TABLE_NAME;

-- 2) 初始数据
SELECT 'user', COUNT(*) FROM user
UNION ALL SELECT 'session', COUNT(*) FROM chat_session
UNION ALL SELECT 'canned', COUNT(*) FROM canned_response
UNION ALL SELECT 'cdp_profile', COUNT(*) FROM cdp_customer_profile
UNION ALL SELECT 'cdp_tag', COUNT(*) FROM cdp_tag
UNION ALL SELECT 'prediction_rule', COUNT(*) FROM prediction_rule;
```

---

## 维护命令

```bash
# 查看表大小
mysql -u root -p -e "
SELECT table_name, ROUND((data_length + index_length) / 1024 / 1024, 2) AS 'MB'
FROM information_schema.tables
WHERE table_schema = 'online_chat' ORDER BY data_length DESC;"

# 备份
mysqldump -u root -p online_chat > backup_$(date +%Y%m%d_%H%M%S).sql

# 恢复
mysql -u root -p online_chat < backup_20260712_020000.sql

# 清空表 (保留结构)
mysql -u root -p -e "USE online_chat; TRUNCATE chat_message; TRUNCATE chat_session;"
```

---

## 数据库迁移 (V3.1 计划)

V3.1 引入 Flyway 做版本化迁移:

```
V3.1.0__add_avatar_to_user.sql
V3.1.1__add_index_on_session_status.sql
V3.1.2__add_proactive_message_table.sql
```

不在 V3.0 范围, 当前用脚本全量重建.
