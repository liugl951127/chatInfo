# 快速上手 (5 分钟)

> 面向所有用户: 客户 / 坐席 / 运营 / 管理员

---

## 🎯 一分钟了解 V3 平台

V3 智能客服平台是一个 **4 阶段 + 2 渠道** 的企业级客服系统:

- **4 阶段**: 智能客服 (Bot) → 人工客服 (Agent) → 社区 (Community) → 数据复盘 (Replay)
- **2 渠道**: 文字 (IM) + 富媒体 (语音/视频/录像)

---

## 👤 客户: 30 秒上手

1. 打开 `https://cs.example.com` (或本地 `http://localhost`)
2. 用演示账号登录: `customer1` / `123456`
3. 点击「开始咨询」→ 选技能 → 即可对话
4. 输入「人工」自动转人工客服
5. 会话结束弹评分, 给 1-5 星

详细操作: [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 第 1-4 章

---

## 🎧 坐席: 1 分钟上手

1. 登录: `agent1` / `123456`
2. 状态默认「在线」, 顶部「抢单」按钮可一键抢客户
3. 左侧等待列表显示 WAITING 客户, 可手动指定接起
4. 输入框有「AI 智能建议」一键应用
5. 「📊 看板」查看今日数据

详细操作: [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 第 5-8 章

---

## 📊 运营: 1 分钟看数据

1. 登录坐席 → 头部「监控」按钮
2. 实时大屏: 4 大指标 + 满意度分布 + 实时事件
3. 5 秒自动刷新 + STOMP 实时推送 (0 延迟)
4. 「📊 看板」看个人数据

详细操作: [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 第 9-11 章

---

## 🔧 管理员: 5 分钟启动

```bash
# 1. 克隆代码
git clone https://github.com/liugl951127/chatInfo.git
cd online-chat

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env: 设置 MYSQL_ROOT_PASSWORD, JWT_SECRET, GRAFANA_ADMIN_PASSWORD

# 3. 一键启动 (15 个服务)
docker-compose up -d

# 4. 等待健康 (~30s)
docker-compose ps  # 所有服务应显示 healthy / Up

# 5. 访问
# 前端:   http://localhost
# API:    http://localhost:9000
# 监控:   http://localhost:3000 (admin/admin)
# 大屏:   http://localhost/monitor
```

详细运维: [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 第 12-14 章

---

## ❓ 出问题?

| 现象 | 解决方案 |
|------|---------|
| 页面打不开 | 看 [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 附录 B |
| WebSocket 连不上 | 查 nginx 配置 (proxy_set_header Upgrade) |
| 录像卡顿 | 网络 ≥ 5 Mbps 上行 |
| 大屏无数据 | 检查 cs-success 是否 healthy |
| 告警太多 | 调 ops/prometheus/alerts.yml 阈值 |
| 客户转人工失败 | 确认至少 1 个坐席在线 |

更多: [OPERATION-MANUAL.docx](OPERATION-MANUAL.docx) 附录 A FAQ

---

## 📚 文档导航

| 角色 | 文档 | 章节 |
|------|------|------|
| 客户 | OPERATION-MANUAL | 第 1-4 章 |
| 坐席 | OPERATION-MANUAL | 第 5-8 章 |
| 运营 | OPERATION-MANUAL | 第 9-11 章 |
| 管理员 | OPERATION-MANUAL | 第 12-14 章 |
| SRE | OPERATION-MANUAL | 第 14 章 + 附录 B |
| 开发者 | DEV-MANUAL | 全部 |
| 架构师 | ARCHITECTURE.html | - |
| DBA | DATABASE.md | - |
| PM | FEATURE-AUDIT.md | - |

---

## 🆘 联系支持

- **邮箱**: support@example.com
- **文档**: [docs/](.)
- **GitHub**: https://github.com/liugl951127/chatInfo
- **版本**: v3.0 (2026-07-12)
