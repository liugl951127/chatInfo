# V3 企业级文档总览

> 更新时间: 2026-07-12
> 项目: V3 智能客服平台 (4 阶段 + 2 渠道架构)

---

## 📂 文档结构

```
docs/
├── README.md                 ← 本文件 (总览 + 索引)
├── DEV-MANUAL.docx           ← 开发手册 (62KB, 18 章 + 7 附录)
├── ARCHITECTURE.html         ← 架构图 (92KB, SVG + 卡片)
├── FLOW.html                 ← 流程图 (120KB, 12+ 流程)
├── DATABASE.md               ← 数据库表结构 (17 张表)
├── FEATURE-AUDIT.md          ← 功能完整性审计报告
└── ops/                      ← 运维配置
    ├── prometheus/
    │   ├── prometheus.yml    ← 9 微服务抓取配置
    │   └── alerts.yml        ← 12 条告警规则
    ├── alertmanager/
    │   └── alertmanager.yml  ← 4 通道路由
    └── grafana/
        ├── cs-sla-dashboard.json   ← 8 面板 SLA 看板
        └── provisioning/           ← 自动加载
```

---

## 📖 文档用途

| 文档 | 受众 | 内容 |
|------|------|------|
| **DEV-MANUAL.docx** | 开发者 / 架构师 | 18 章开发手册 + 7 附录 (实时推送/企业能力/SLA/部署) |
| **ARCHITECTURE.html** | 全员 / 客户演示 | 9 微服务架构 + V3 4 阶段 + 实时架构 + SLA 架构 |
| **FLOW.html** | 全员 | 8 核心流程 + 4 V3.0 新增流程 (实时推送/SLA/E2E/AI) |
| **DATABASE.md** | DBA / 开发者 | 17 张表完整结构 + 索引 + 数据流 + 性能优化 |
| **FEATURE-AUDIT.md** | PM / 客户 | 40 项功能审计 (100% 完整实现) |
| **ops/** | SRE / 运维 | Prometheus + AlertManager + Grafana 全栈配置 |

---

## 🔢 项目数据

| 维度 | 数据 |
|------|------|
| 后端微服务 | 11 (cs-auth/im/cdp/community/prediction/customer-success/ai/video/voice/gateway + cs-common) |
| 后端端点 | 84 (74 业务 + 10 health) |
| Java 代码 | 11,381 行 |
| SQL 表 | 17 (分布在 5 个数据库) |
| 前端页面 | 6 (Login/Customer/Agent/Community/Replay/RealtimeMonitor) |
| 前端组件 | 12 |
| 前端 composables | 9 |
| 前端 API 客户端 | 11 |
| 前端代码 | 8,000+ 行 |
| Docker 服务 | 15 (9 微服务 + frontend + 2 数据库 + 3 监控) |
| Prometheus 告警规则 | 12 |
| Grafana 面板 | 8 |
| 企业能力 | 限流/脱敏/重试/压缩/SLA/告警 |
| 实时机制 | STOMP /topic/realtime + 大屏 5s 兜底 |
| 测试账号 | 6 (3 客户 + 3 坐席) |

---

## 📊 功能完整度

| 维度 | 完成度 |
|------|--------|
| 核心功能 (40 项) | **40/40 = 100%** |
| 业务端点接入 | **74/74 = 100%** |
| 前端页面交互 | **6/6 = 100%** |
| 端到端流程 | **完整闭环** |
| 企业级能力 | **限流/脱敏/重试/压缩/SLA/告警 全部就绪** |
| 生产部署 | **Docker Compose 一键启动** |
| 监控告警 | **Prometheus + AlertManager + Grafana 全栈** |

---

## 🚀 快速开始

### 阅读顺序
1. **新成员**: 先看 [DEV-MANUAL.docx](DEV-MANUAL.docx) 第 1-3 章
2. **开发者**: 看 [DEV-MANUAL.docx](DEV-MANUAL.docx) 第 8-11 章 (端点 + 交互)
3. **架构师**: 看 [ARCHITECTURE.html](ARCHITECTURE.html) + [DEV-MANUAL.docx](DEV-MANUAL.docx) 第 10 章
4. **DBA**: 看 [DATABASE.md](DATABASE.md)
5. **运维/SRE**: 看 [ops/](../ops/) + [DEV-MANUAL.docx](DEV-MANUAL.docx) 第 15-16 章
6. **PM/客户**: 看 [FEATURE-AUDIT.md](FEATURE-AUDIT.md)

### 开发流程
```bash
# 1. 启动
cp .env.example .env
docker-compose up -d

# 2. 访问
# 前端:  http://localhost:80
# API:   http://localhost:9000
# 监控:  http://localhost:3000 (admin/admin)

# 3. 测试
# 客户: customer1 / 123456
# 坐席: agent1 / 123456

# 4. 大屏
# 登录 agent1 → 头部"监控"按钮 → /monitor
```

---

## 📞 联系方式

- **项目**: V3 智能客服平台
- **架构**: 4 阶段 + 2 渠道
- **版本**: 3.0.0
- **维护**: Mavis Agent Team
- **更新**: 2026-07-12
