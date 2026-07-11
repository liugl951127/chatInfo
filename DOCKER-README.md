# Online Chat · Docker 启动指南

> 让 9 个后端微服务 + 前端 + 网关 + 数据库 + 缓存, 一键起栈.
> 每个后端服务也能独立 `docker run`, 不依赖其他服务.

---

## 目录

- [架构总览](#架构总览)
- [前置条件](#前置条件)
- [一键启动全栈](#一键启动全栈)
- [单服务独立运行](#单服务独立运行)
- [端口与路由速查](#端口与路由速查)
- [常见问题](#常见问题)

---

## 架构总览

```
┌────────────────────────────────────────────────────────────────────┐
│                          docker-compose 栈                          │
│                                                                    │
│  ┌──────────┐  HTTP  ┌────────────┐  HTTP  ┌──────────────────┐   │
│  │ frontend │ ─────► │ cs-gateway │ ─────► │  9 个后端微服务  │   │
│  │ (nginx)  │        │  :9000     │        │  :8081 - :8089   │   │
│  │  :80     │  WS    │            │  WS    └──────────────────┘   │
│  └──────────┘ ─────► └────────────┘ ─────►                          │
│                                  │           │                     │
│                                  ▼           ▼                     │
│                            ┌─────────┐  ┌─────────┐                │
│                            │ mariadb │  │  redis  │                │
│                            │  :3306  │  │  :6379  │                │
│                            └─────────┘  └─────────┘                │
└────────────────────────────────────────────────────────────────────┘
```

**两种运行模式:**

| 模式 | Profile | 数据库 | Redis | 用途 |
|---|---|---|---|---|
| `docker-compose` 全栈 | `default` (mariadb) | mariadb 容器 | redis 容器 | 完整演示 / 集成测试 |
| `docker run` 独立运行 | `standalone` (H2) | H2 内存库 | 已禁用 | 单服务调试 / 演示 |

---

## 前置条件

- **Docker Engine** ≥ 20.10
- **docker-compose** ≥ 1.29 (或 Docker Compose V2 plugin)
- **磁盘空间** ≥ 5 GB (拉镜像 + 构建)
- **内存** ≥ 4 GB (10 个 JVM 容器同时跑)

```bash
docker --version
docker-compose --version
# 或
docker compose version
```

---

## 一键启动全栈

### 1. 准备环境变量

```bash
cd /workspace/online-chat
cp .env.example .env
# 按需修改密码 / 端口, 默认值即可直接跑
```

### 2. 启动

```bash
# 后台启动
docker-compose up -d --build

# 前台启动 (实时看日志)
docker-compose up --build
```

第一次 `up` 会触发镜像构建, 大约 5-10 分钟 (Maven 下载依赖 + 9 个微服务编译).

### 3. 验证

```bash
# 容器状态
docker-compose ps

# 健康检查
curl http://localhost:8081/actuator/health    # cs-auth
curl http://localhost:8082/actuator/health    # cs-im
curl http://localhost:8087/api/ai/health      # cs-ai

# 访问前端
open http://localhost:80
```

### 4. 停止

```bash
docker-compose down              # 停止容器, 保留数据卷
docker-compose down -v           # 停止 + 删数据卷 (mariadb/redis 数据)
```

### 5. 重建某个服务

```bash
docker-compose up -d --build cs-im
docker-compose logs -f --tail=100 cs-im
```

---

## 单服务独立运行

每个 cs-xxx 服务都有自己的 Dockerfile 和 `application-standalone.yml`,
无需 mariadb/redis 即可启动, 使用 H2 内存库.

### 示例: 独立跑 cs-auth

```bash
cd /workspace/online-chat/backend/cs-auth

# 直接 docker run (镜像名 chat-cs-auth 由 docker-compose 构建)
docker run -d --name chat-cs-auth \
  -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=standalone \
  chat-cs-auth

# 查看日志
docker logs -f chat-cs-auth

# 调用
curl http://localhost:8081/api/auth/health

# 停止
docker stop chat-cs-auth && docker rm chat-cs-auth
```

### 示例: 独立跑 cs-ai

```bash
cd /workspace/online-chat/backend/cs-ai

# 构建
docker build -t chat-cs-ai .

# 运行
docker run -d --name chat-cs-ai \
  -p 8087:8087 \
  -e SPRING_PROFILES_ACTIVE=standalone \
  chat-cs-ai

# 测试
curl http://localhost:8087/api/ai/health
curl -X POST http://localhost:8087/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "你好, 我想退款"}'
```

### 示例: 独立跑 cs-im (WebSocket)

```bash
docker run -d --name chat-cs-im \
  -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=standalone \
  chat-cs-im

# H2 控制台
open http://localhost:8082/h2-console
# JDBC URL: jdbc:h2:mem:im  user: sa  password: (空)
```

> **注**: standalone 模式已禁用 Redis 自动装配, cs-im 中需要 Redis 的功能
> (坐席在线状态、离线消息、Pub/Sub) 在缺省 Redis 时调用会抛异常. 业务上
> 仅做"启动 + 接口可达"验证, 完整功能请走 docker-compose 全栈模式.

---

## 端口与路由速查

### 主机侧端口

| 服务 | 容器 | 主机端口 | 内部端口 | 备注 |
|---|---|---|---|---|
| frontend | chat-frontend | **80** | 80 | nginx, SPA 静态 |
| cs-gateway | chat-cs-gateway | **9000** | 9000 | 网关 (docker profile) |
| cs-auth | chat-cs-auth | **8081** | 8081 | 登录/注册 |
| cs-im | chat-cs-im | **8082** | 8082 | IM + WebSocket |
| cs-cdp | chat-cs-cdp | **8083** | 8083 | 数字孪生 |
| cs-community | chat-cs-community | **8084** | 8084 | 社区 |
| cs-prediction | chat-cs-prediction | **8085** | 8085 | 预见式 |
| cs-customer-success | chat-cs-customer-success | **8086** | 8086 | 客户成功 |
| cs-ai | chat-cs-ai | **8087** | 8087 | Java 自研 AI |
| cs-video | chat-cs-video | **8088** | 8088 | 视频 |
| cs-voice | chat-cs-voice | **8089** | 8089 | 智能电话 |
| mariadb | chat-mariadb | **3306** | 3306 | MySQL/MariaDB |
| redis | chat-redis | **6379** | 6379 | 缓存 + Pub/Sub |

### 网关路由 (cs-gateway, docker profile)

| 路径前缀 | 转发到 |
|---|---|
| `/auth/**` | `cs-auth:8081` |
| `/api/im/**` | `cs-im:8082` |
| `/ws/**` | `cs-im:8082` (WebSocket) |
| `/api/cdp/**` | `cs-cdp:8083` |
| `/api/community/**` | `cs-community:8084` |
| `/api/prediction/**` | `cs-prediction:8085` |
| `/api/success/**` | `cs-customer-success:8086` |
| `/api/ai/**` | `cs-ai:8087` |
| `/api/video/**` | `cs-video:8088` |
| `/api/voice/**` | `cs-voice:8089` |

### nginx 反代 (frontend)

| 路径 | 动作 |
|---|---|
| `/` | SPA `index.html` |
| `/api/**` | 反代到 `cs-gateway:9000` |
| `/auth/**` | 反代到 `cs-gateway:9000` |
| `/ws/**` | 反代到 `cs-gateway:9000` (WebSocket) |
| `/healthz` | 返回 200 |
| `/h2-console/**` | 反代 (仅 standalone) |

---

## 模块清单

### 后端微服务 (9 个)

| 模块 | 端口 (standalone / 默认) | 数据库 | 是否需要 Redis | 描述 |
|---|---|---|---|---|
| cs-auth | 8081 / 9001 | 用户表 | 否 | 登录 / 注册 / JWT 签发 |
| cs-im | 8082 / 9002 | 会话/消息 | **是** | IM 核心 + WebSocket (STOMP) |
| cs-cdp | 8083 / 9006 | 用户画像 | 否 | 数字孪生 360° |
| cs-community | 8084 / 9008 | UGC/帖子 | 否 | 群体智能社区 |
| cs-prediction | 8085 / 9005 | 事件流 | **是** | 预见式 (流失/价值/异常) |
| cs-customer-success | 8086 / 9007 | 健康分 | 否 | 客户成功 (onboarding/续约) |
| cs-ai | 8087 / 8087 | (无) | 否 | Java 自研 AI (FAQ/意图/情感) |
| cs-video | 8088 / 9009 | 录制 | 否 | 视频会话 (WebRTC) |
| cs-voice | 8089 / 9010 | 通话 | 否 | 智能电话 (ASR/TTS) |

> `cs-common` 是公共库, 不独立运行.

### 共享 AI 库 (`cs-common/ai`)

被 `cs-ai` / `cs-im` / `cs-prediction` 等服务共用:
- `LocalAiService` — 端到端 chat 决策
- `FaqEngine` — FAQ TF-IDF 检索 (25 条内置)
- `IntentClassifier` — 11 类意图识别
- `SentimentAnalyzer` — 情感分析 (ANGRY/SAD/NEUTRAL/HAPPY)
- `TfIdfEmbedder` — 256 维向量化

---

## 常用命令

```bash
# 查看所有服务日志
docker-compose logs -f

# 查看单个服务
docker-compose logs -f cs-im

# 容器内调试
docker exec -it chat-cs-im bash

# 重启单个服务
docker-compose restart cs-im

# 进入 mariadb
docker exec -it chat-mariadb mysql -uchatuser -pchatpass_2026 online_chat

# 进入 redis
docker exec -it chat-redis redis-cli -a ${REDIS_PASSWORD}

# 清理
docker-compose down -v
docker system prune -a
```

---

## 常见问题

### Q1: `mvn install` 阶段下载依赖慢

Dockerfile 默认走 Maven 中央仓库. 国内构建可在 `backend/pom.xml` 顶部
加 `<repositories>` 指向阿里云, 或在 host 上配置 `~/.m2/settings.xml` 镜像.

### Q2: 容器启动后立刻退出

```bash
docker-compose logs cs-im | tail -50
```

常见原因:
- 8081 端口被占用: 修改 `.env` 中 `PORT_AUTH=18081` 等
- mariadb 还没就绪: `depends_on` 配了 `service_healthy`, 等待即可
- JWT 密钥不匹配: 确认所有容器读到的 `JWT_SECRET` 相同

### Q3: standalone 模式 cs-im 报 Redis 错误

`application-standalone.yml` 已 `exclude` RedisAutoConfiguration, 但 cs-im
代码里 `@Autowired StringRedisTemplate` 仍会被实例化. 解决:

```bash
# 方案 A: 启动一个 redis 容器并把端口指向 6379
docker run -d --name chat-redis -p 6379:6379 redis:7-alpine

# 方案 B: 改 standalone yml 不 exclude redis, 让它真的连上
# (移除 spring.autoconfigure.exclude 块)
```

### Q4: 前端打包很慢

`node:18-alpine` 首次 `npm install` 慢属正常. 改用 `node:18` 或加 `.npmrc` 镜像:

```dockerfile
RUN echo "registry=https://registry.npmmirror.com" > .npmrc
```

### Q5: H2 Console 怎么进

standalone 模式:
```
http://localhost:8081/h2-console  (cs-auth)
http://localhost:8082/h2-console  (cs-im)
...
```
JDBC URL: `jdbc:h2:mem:<service>`
用户名: `sa`, 密码: (空)

### Q6: schema.sql 没自动跑

mariadb 容器只在**首次启动且数据卷为空**时执行 `docker-entrypoint-initdb.d/*.sql`.
若已建过库, 需:
```bash
docker-compose down -v    # 删 mariadb-data 卷
docker-compose up -d mariadb
```

### Q7: cs-ai 模块是新加的吗

是. `cs-ai` 在原工程骨架里只有 `cs-common/ai` 公共库, 此次新增独立的
微服务 `cs-ai` (8087), 复用 cs-common/ai 下的 LocalAiService / FaqEngine
等本地推理组件, 独立 `docker run` 时不依赖 Python m3-adapter.

---

## 文件清单

```
online-chat/
├── docker-compose.yml                      # 一键启动
├── .env.example                            # 环境变量模板
├── DOCKER-README.md                        # 本文件
│
├── backend/
│   ├── cs-common/                          # 公共库 (无 Dockerfile)
│   ├── cs-gateway/
│   │   ├── Dockerfile
│   │   └── src/main/resources/application-docker.yml
│   ├── cs-auth/
│   │   ├── Dockerfile
│   │   └── src/main/resources/application-standalone.yml
│   ├── cs-im/
│   │   ├── Dockerfile
│   │   └── src/main/resources/application-standalone.yml
│   ├── cs-cdp/                  ...        # 同上结构
│   ├── cs-community/            ...
│   ├── cs-prediction/           ...
│   ├── cs-customer-success/     ...
│   ├── cs-ai/                   ...        # 新增模块
│   ├── cs-video/                ...
│   └── cs-voice/                ...
│
├── frontend/
│   ├── Dockerfile                          # node:18-alpine → nginx:alpine
│   └── nginx.conf                          # SPA + /api/ /ws/ 反代
│
└── sql/
    └── schema.sql                          # mariadb 首次启动初始化
```

---

## 下一步

- **生产部署**: 把 `.env` 里的密码改成强密码, 启用 TLS, 走 Kubernetes.
- **CI/CD**: 在 `Dockerfile` 的 build 阶段用 `mvn -B verify` 跑测试.
- **监控**: 给每个容器加 prometheus exporter, 在 gateway 上接 Micrometer.
- **日志聚合**: 接 ELK / Loki, 容器 stdout 用 json log driver.

---

**最后更新**: 2026-07-11 · 与 `docker-compose.yml` 版本一致
