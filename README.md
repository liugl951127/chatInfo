# Online Chat · 客户 ↔ 坐席会话系统

> 基于 Spring Cloud + Vue 3 + MySQL + Redis + WebSocket (STOMP) 的轻量级在线客服系统。
> 支持 JWT 鉴权、客户/坐席双端、实时消息、坐席抢单、离线消息、Redis Pub/Sub 跨实例推送。

## 技术栈

| 层 | 选型 |
|---|---|
| 网关 | Spring Cloud Gateway 4.x |
| 微服务 | Spring Boot 3.2 + Spring Cloud 2023 |
| 鉴权 | JJWT (HS256) + BCrypt |
| 实时 | Spring WebSocket + STOMP |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5 |
| 缓存/队列/广播 | Redis (String/List/Set/Pub-Sub) |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + Vue Router + @stomp/stompjs |
| 文档 | Knife4j (OpenAPI 3) |

## 模块划分

```
online-chat/
├── backend/                       # Spring Cloud 后端 (Maven 多模块)
│   ├── pom.xml                    # parent
│   ├── cs-common/                 # 公共: JWT/Redis/CORS/异常/DTO
│   ├── cs-gateway/   :9000        # 网关: 路由 + 全局 JWT 校验
│   ├── cs-auth/      :9001        # 鉴权: 登录/注册/JWT签发
│   └── cs-im/        :9002        # IM 核心: WebSocket/会话/消息/分发
├── frontend/                      # Vue 3 前端
│   └── package.json
└── sql/schema.sql                 # MySQL DDL + 初始数据
```

## 数据流概览

```
[客户浏览器]                                          [坐席浏览器]
   │  HTTPS /auth/login                                    │
   ▼                                                       ▼
  cs-gateway (9000) ────► cs-auth (9001)                 ...
   │                          │ JWT                       │
   │                          ▼                          │
   │  Authorization: Bearer ...                            │
   ▼                                                       ▼
   cs-im (9002) ◄──────── HTTP REST ─────────►  cs-im (9002)
       │  ws://gw/ws/customer (STOMP)            ws://gw/ws/agent
       │  /app/send/{sessionId}                   /app/send/{sessionId}
       │  /user/queue/messages                    /user/queue/messages
       │  /topic/sessions/new ◄──────── Pub/Sub ─┘
       │
       └──► Redis (Pub/Sub) ── 跨实例推送 ──► 其它 cs-im 节点
       └──► MySQL (chat_session / chat_message 持久化)
```

## 快速开始

### 1. 准备环境
- JDK 17+
- Maven 3.8+
- Node 18+ / npm 9+
- MySQL 8.0+ (root/root, 或自行修改 application.yml)
- Redis 6.0+ (默认 localhost:6379)

### 2. 初始化数据库
```bash
mysql -uroot -p < sql/schema.sql
```
脚本会创建 `online_chat` 数据库, 三张表 (user / chat_session / chat_message), 以及演示账号 (密码均为 `123456`):

| 用户名 | 角色 | 昵称 |
|---|---|---|
| customer1 | CUSTOMER | 小明 |
| customer2 | CUSTOMER | 小红 |
| agent1 | AGENT | 客服-小张 |
| agent2 | AGENT | 客服-小李 |

### 3. 启动后端 (按顺序)
```bash
cd backend
mvn clean install -DskipTests

# 三个服务分别启动 (各开一个终端)
mvn -pl cs-gateway spring-boot:run
mvn -pl cs-auth    spring-boot:run
mvn -pl cs-im      spring-boot:run
```

启动成功后:
- 网关:    http://localhost:9000
- 鉴权:    http://localhost:9001/doc.html (Knife4j)
- IM:      http://localhost:9002/doc.html (Knife4j)

### 4. 启动前端
```bash
cd frontend
npm install
npm run dev
# 默认 http://localhost:5173
```

打开两个浏览器窗口:
1. 第一个窗口 → 用 `customer1 / 123456` 登录 → 进入客户页
2. 第二个窗口 → 用 `agent1 / 123456` 登录 → 进入坐席页

坐席端点击右上角「抢一单」接入客户会话, 双方即可实时聊天。

## 核心接口

### REST (走网关 9000)

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/auth/register` | 注册 (仅 CUSTOMER) | 公开 |
| POST | `/auth/login` | 登录拿 JWT | 公开 |
| GET  | `/auth/me` | 当前用户 | 登录 |
| POST | `/api/im/session/create` | 客户创建会话 | 客户 |
| POST | `/api/im/session/claim` | 坐席抢单 | 坐席 |
| GET  | `/api/im/session/mine` | 我的会话 | 登录 |
| GET  | `/api/im/session/waiting` | 等待队列 (坐席) | 坐席 |
| POST | `/api/im/session/{id}/close` | 关闭会话 | 会话参与方 |
| GET  | `/api/im/session/bootstrap` | 启动状态 | 登录 |
| GET  | `/api/im/session/offline/drain` | 拉取并清空离线消息 | 登录 |
| GET  | `/api/im/session/{id}/messages?limit=50` | 历史消息 | 会话参与方 |

### WebSocket (STOMP)

- **连接入口**:
  - 客户: `ws://localhost:9000/ws/customer?token=<JWT>` 或在 STOMP CONNECT 帧带 `Authorization: Bearer <JWT>` header
  - 坐席: `ws://localhost:9000/ws/agent?token=<JWT>`
- **客户端订阅**:
  - `/user/queue/messages` — 自己收消息 (含自己发的回显 + 对方发的)
  - `/user/queue/errors` — 服务端异常
  - `/topic/sessions/new` — (坐席) 新会话广播
- **客户端发送**:
  - `/app/send/{sessionId}` — 发消息到指定会话, body 为 `MessageDTO`

```js
// 前端示例
const client = new StompClient({ token })
client.connect('/ws/customer')
client.subscribe('/user/queue/messages', (msg) => console.log(msg))
client.send(`/app/send/${sessionId}`, {
  msgType: 'TEXT',
  content: '你好'
})
```

## 设计要点

### JWT 鉴权
- **网关层** (`cs-gateway`): `AuthGlobalFilter` 校验 `Authorization: Bearer ...`, 通过后把 `X-User-*` 头透传给下游; 白名单: `/auth/login`, `/auth/register`, `/ws/**`
- **服务层** (`cs-auth` / `cs-im`): `JwtAuthInterceptor` (来自 `cs-common`) 重复校验, 即使绕过网关也安全; 同时把用户信息写入 `UserContext` (基于 `RequestContextHolder`)

### STOMP 用户标识
- 握手阶段 `HandshakeAuthInterceptor` 解析 JWT 写入 session attributes
- CONNECT 阶段 `StompAuthChannelInterceptor` 读取 attributes 设置 `Principal` 并标记用户上线
- DISCONNECT 阶段清理在线状态

### 跨实例推送
- `WsPushService` 监听 Redis Pub/Sub channel `chat:ws:push:{userId}`
- 即使消息来自 cs-im 节点 A, 用户连接到节点 B, 也能通过 Redis 广播到 B, 然后 `convertAndSendToUser` 推到本地 session
- 单实例部署可禁用 Redis Pub/Sub 部分, 直接 `convertAndSendToUser` 即可

### 离线消息
- 接收方不在线时, 消息进入 Redis List `chat:offline:{userId}` (上限 200, TTL 24h)
- 用户上线后调用 `/api/im/session/offline/drain` 一次性拉取

### 坐席分配
- 客户创建会话 → 若有在线坐席 (`chat:agent:online` set) → 立即分配, 会话直接 ACTIVE
- 若无坐席 → 进入 `chat:queue:waiting` 列表, 广播 `/topic/sessions/new`
- 坐席主动 `POST /api/im/session/claim` 抢单 (LPOP 队列头)

## 配置项

所有服务共用 `chat.jwt.secret`, 通过 `application.yml` 覆盖:

```yaml
chat:
  jwt:
    secret: online-chat-jwt-secret-please-change-in-production  # 生产必改 (≥32 字符)
    ttl-ms: 86400000                                              # 24h
```

数据库账号/密码、Redis 地址在每个服务的 `application.yml` 里改。

## 已知简化

- 暂未引入 Nacos / Sentinel / Kafka, 单机演示已够用, 上生产建议:
  - **Nacos** 替换 `application.yml` 做配置中心和服务发现
  - **Kafka** 异步化消息持久化 (`MessageService.handleIncoming` 加 `@Async`)
  - **Sentinel** 网关限流 + 熔断
- 未实现: 输入指示符、已读回执、图片/文件上传、聊天记录导出、转接会话、坐席工作量统计
- 密码默认 123456 + BCrypt, 生产请强制首次登录改密

## 目录速览

```
backend/
├── cs-common/
│   └── src/main/java/com/chat/common/
│       ├── api/ApiResponse.java
│       ├── config/{RedisConfig, WebMvcConfigSupport, JwtAuthInterceptor}.java
│       ├── constant/CommonConstants.java
│       ├── dto/{LoginDTO, LoginVO, MessageDTO}.java
│       └── security/{JwtUtil, UserContext}.java
├── cs-gateway/src/main/java/com/chat/gateway/
│   ├── CsGatewayApplication.java
│   ├── config/CorsConfig.java
│   └── filter/AuthGlobalFilter.java
├── cs-auth/src/main/java/com/chat/auth/
│   ├── CsAuthApplication.java
│   ├── controller/AuthController.java
│   ├── service/AuthService.java
│   ├── entity/User.java
│   └── mapper/UserMapper.java
└── cs-im/src/main/java/com/chat/im/
    ├── CsImApplication.java
    ├── config/{WebSocketConfig, GlobalExceptionHandler}.java
    ├── controller/{SessionController, MessageController}.java
    ├── entity/{ChatSession, ChatMessage}.java
    ├── mapper/{ChatSessionMapper, ChatMessageMapper}.java
    ├── interceptor/{HandshakeAuthInterceptor, StompAuthChannelInterceptor}.java
    └── service/{SessionService, MessageService, PresenceService,
                 OfflineMessageStore, WsPushService}.java

frontend/src/
├── main.js
├── App.vue
├── router/index.js
├── stores/user.js
├── api/{axios, auth, im}.js
├── utils/ws-client.js
└── views/{Login, Customer, Agent}.vue
```

## License

MIT — 仅作学习演示用。