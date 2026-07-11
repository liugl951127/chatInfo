# V3 企业级文档总览

> 更新时间: 2026-07-12
> 项目: V3 智能客服平台 (4 阶段 + 2 渠道架构)

---

## 文档结构

```
docs/
├── README.md                 ← 本文件 (总览 + 索引)
├── DEV-MANUAL.docx           ← 开发手册 (62KB, 18 章 + 7 附录)
├── OPERATION-MANUAL.docx     ← 操作手册 (46KB, 客户/坐席/运营/管理员)
├── ARCHITECTURE.html         ← 架构图 (96KB, SVG + 卡片)
├── FLOW.html                 ← 流程图 (124KB, 16 流程)
├── DATABASE.md               ← 数据库表结构 (19 张表)
├── FEATURE-AUDIT.md          ← 功能完整性审计报告
├── UX-ENHANCEMENTS.md        ← V6 客户体验增强详情
└── ops/                      ← 运维配置
    ├── prometheus/           ← 9 微服务抓取 + 12 告警规则
    ├── alertmanager/         ← 4 通道路由
    └── grafana/              ← 8 面板 SLA 看板
```

---

## 文档用途

| 文档 | 受众 | 大小 | 内容 |
|------|------|------|------|
| **DEV-MANUAL.docx** | 开发者 / 架构师 | 62 KB | 18 章开发手册 + 7 附录 |
| **OPERATION-MANUAL.docx** | 客户/坐席/运营/管理员/SRE | 46 KB | 4 篇 + 4 附录 业务操作手册 |
| **ARCHITECTURE.html** | 全员 / 客户演示 | 96 KB | 9 微服务架构 + 实时推送 + SLA + 企业能力 |
| **FLOW.html** | 全员 | 124 KB | 16 流程图 (含 V3.0 新增) |
| **DATABASE.md** | DBA / 开发者 | 18 KB | 19 张表完整结构 + 索引 + 数据流 |
| **FEATURE-AUDIT.md** | PM / 客户 | 6.5 KB | 40 项功能审计 (100% 完整) |
| **UX-ENHANCEMENTS.md** | 开发者 / PM | 5 KB | V6 客户体验增强详情 |
| **ops/** | SRE / 运维 | - | Prometheus + AlertManager + Grafana |

---

## 文档定位

### 开发者文档
- **DEV-MANUAL.docx** - 面向开发, 包含代码/接口/算法/部署
- **DATABASE.md** - 数据库表结构 + 索引 + 性能优化
- **ARCHITECTURE.html** - 架构图, 适合演示
- **FLOW.html** - 流程图, 适合 onboarding

### 业务操作文档
- **OPERATION-MANUAL.docx** - 面向业务, 包含 5 类用户操作指南

  4 篇:
  1. 客户使用指南 (注册/咨询/转人工/评分)
  2. 坐席工作指南 (登录/接单/转接/富媒体)
  3. 运营管理指南 (看板/大屏/健康分)
  4. 管理员配置 (限流/脱敏/告警/监控)

  4 附录:
  - A. 常见问题 FAQ
  - B. 故障排查
  - C. 合规与安全
  - D. 术语表

---

## 文档对比

| 维度 | DEV-MANUAL | OPERATION-MANUAL |
|------|-----------|------------------|
| 受众 | 开发者 | 业务/客服/运维 |
| 内容 | 代码/接口/算法 | 操作步骤/截图位置/FAQ |
| 章节 | 18 章 + 7 附录 | 4 篇 + 4 附录 |
| 大小 | 62 KB | 46 KB |
| 字数 | ~30,000 字 | ~20,000 字 |
| 表格 | 32 | 12 |
| 风格 | 技术 + 代码 | 业务 + 步骤 |

---

## 阅读顺序

### 新成员 (客户/坐席)
1. OPERATION-MANUAL 第 1-2 章 (客户) 或 第 5-6 章 (坐席)
2. OPERATION-MANUAL 附录 A (常见问题)

### 新开发者
1. ARCHITECTURE.html (了解全貌)
2. DEV-MANUAL.docx 第 1-3 章 (快速上手)
3. DEV-MANUAL.docx 第 8-11 章 (端点 + 交互)
4. DATABASE.md (表结构)

### 架构师
1. ARCHITECTURE.html
2. DEV-MANUAL.docx 第 10-11 章 (企业能力 + SLA)
3. FLOW.html (流程图)

### DBA
1. DATABASE.md (表结构 + 索引 + 性能)

### SRE / 运维
1. ops/ 目录 (Prometheus + AlertManager + Grafana)
2. DEV-MANUAL.docx 第 15-16 章 (部署 + 监控)
3. OPERATION-MANUAL.docx 第 14 章 (监控运维) + 附录 B (故障排查)

### PM / 客户
1. FEATURE-AUDIT.md (功能清单 + 完整度)
2. ARCHITECTURE.html (架构总览)
3. OPERATION-MANUAL.docx (业务指南)

---

## 项目数据

| 维度 | 数据 |
|------|------|
| 后端微服务 | 11 (auth/im/cdp/community/prediction/customer-success/ai/video/voice/gateway + common) |
| 后端端点 | 84 (74 业务 + 10 health) |
| Java 代码 | 11,381 行 |
| SQL 表 | 19 (单库, 跨模块命名) |
| 前端页面 | 6 (Login/Customer/Agent/Community/Replay/RealtimeMonitor) |
| 前端组件 | 12 |
| 前端 composables | 17 (V6 新增 8) |
| 前端 API 客户端 | 11 |
| 前端代码 | 8,000+ 行 |
| Docker 服务 | 15 |
| Prometheus 告警规则 | 12 |
| Grafana 面板 | 8 |
| 企业能力 | 限流/脱敏/重试/压缩/SLA/告警 |
| 实时机制 | STOMP /topic/realtime + 5s 兜底 |
| 测试账号 | 6 (3 客户 + 3 坐席) |

---

## 功能完整度

| 维度 | 完成度 |
|------|--------|
| 核心功能 (40 项) | **40/40 = 100%** |
| 业务端点接入 | **74/74 = 100%** |
| 前端页面交互 | **6/6 = 100%** |
| 端到端流程 | **完整闭环** |
| 企业级能力 | **限流/脱敏/重试/压缩/SLA/告警 全部就绪** |
| 生产部署 | **Docker Compose 一键启动** |
| 监控告警 | **Prometheus + AlertManager + Grafana 全栈** |
| 客户体验 | **V6 草稿/快捷键/拖拽/暗色主题/快捷键帮助** |

---

## 快速开始

```bash
# 1. 启动
cp .env.example .env
docker-compose up -d

# 2. 访问
# 前端:  http://localhost:80
# API:   http://localhost:9000
# 监控:  http://localhost:3000 (admin/admin)
# 大屏:  http://localhost/monitor

# 3. 测试账号
# 客户: customer1 / 123456
# 坐席: agent1 / 123456

# 4. 详细操作: 参考 OPERATION-MANUAL.docx
```

---

## 联系方式

- **项目**: V3 智能客服平台
- **架构**: 4 阶段 + 2 渠道
- **版本**: 3.0.0
- **维护**: V3 Team
- **更新**: 2026-07-12
- **支持**: support@example.com
