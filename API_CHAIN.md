# Online Chat 接口链路文档

> 系统: `online-chat` (Spring Cloud 微服务 + Vue 3 SPA)
> 仓库: `https://github.com/liugl951127/chatInfo`
> 当前版本: `cae36ef` (main)
> 端口约定: `cs-auth=9001`, `cs-gateway=9000`, `cs-im=9002`

---

## 0. 架构概览

```
                  ┌──────────────────────────────┐
                  │   Vue 3 SPA (5173 dev)       │
                  │   - Login / Customer / Agent │
                  │   - Replay                   │
                  │   - record-sdk.js (录制SDK)  │
                  └──────────────┬───────────────┘
                                 │ HTTPS (REST + WS)
                                 ▼
                  ┌──────────────────────────────┐
                  │  cs-gateway (9000)           │
                  │  - AuthGlobalFilter (JWT)    │
                  │  - 路由转发 /api/im/** /auth │
                  │  - WS 握手转发 /ws/**        │
                  └────────┬─────────────┬───────┘
                           ▼             ▼
              ┌─────────────────┐  ┌──────────────┐
              │ cs-auth (9001)  │  │ cs-im (9002) │
              │ - 注册/登录     │  │ - 会话/消息  │
              │ - JWT 签发      │  │ - STOMP 推送 │
              │                 │  │ - 录制存储   │
              └─────────────────┘  └──────┬───────┘
                                          │
                                ┌─────────┴─────────┐
                                ▼                   ▼
                        ┌──────────┐         ┌──────────┐
                        │ MySQL    │         │ Redis    │
                        │ 3306     │         │ 6379     │
                        │ chat_*   │         │ presence │
                        └──────────┘         │ ws pubsub│
                                            └──────────┘
```

**前后端契约**: 后端返回 `{code, message, data}` 三元组；`code=0` 成功，`data` 为业务载荷。axios 拦截器已统一解包。

---

## 1. 鉴权模块 (cs-auth)

| 接口 | 方法 | 路径 | 含义 | 实现原理 |
|---|---|---|---|---|
| 登录 | `POST` | `/auth/login` | 用户名密码登录, 返回 JWT | `bcrypt.matches(rawPwd, dbHash)` → JWT(HS512) 签发, claims: uid/role/nickname |
| 注册 | `POST` | `/auth/register` | 新用户注册 | 同上, 默认 role=CUSTOMER |
| 注册管理员 | `POST` | `/auth/register-admin` | (开发用) 创建 admin | 同上, 强制 role=ADMIN |

**前端调用链**:
```
Login.vue 提交
  ↓ axios.post('/auth/login', {username, password})
  ↓ axios 拦截器解 data → userStore.set({token, role, ...})
  ↓ router.replace(role==='AGENT' ? '/agent' : '/customer')
```

**JWT 透传机制**:
```
请求进 cs-gateway
  ↓ AuthGlobalFilter 校验 JWT, 校验通过后 mutate header 注入 X-User-Id 等
  ↓ 转发到 cs-im / cs-auth
  ↓ 下游服务的 JwtAuthInterceptor 再次校验 JWT (防绕过网关)
  ↓ 校验通过 → UserContext.set(uid, username, role, nickname) (ThreadLocal)
  ↓ Controller 通过 UserContext.userId() 获取当前用户
```

**Token 生命周期**: 默认 24h 过期, 不在客户端做刷新 (简化)。前端用 Pinia `useUserStore.token` 持久化到 `localStorage`。

---

## 2. 会话模块 (cs-im `/api/im/session/**`)

| 接口 | 方法 | 含义 | 角色 |
|---|---|---|---|
| `/mine` | GET | 我的会话列表 (含 peerOnline) | 任意已登录 |
| `/create?skill=` | POST | 创建会话, 自动分配坐席 | CUSTOMER |
| `/claim` | POST | 抢单 (从等待队列取一个) | AGENT |
| `/waiting` | GET | 查看等待队列 (admin) | AGENT/ADMIN |
| `/{id}/transfer?toAgentId=&reason=` | POST | 转接到其他坐席 | AGENT |
| `/{id}/request-transfer?preferredSkill=` | POST | **客户申请转接客服** | CUSTOMER |
| `/{id}/rate?rating=&comment=` | POST | CSAT 评分 | CUSTOMER |
| `/{id}/close` | POST | 关闭会话 (会触发 CSAT 弹窗) | AGENT/CUSTOMER |
| `/{id}/exit?reason=` | POST | **客户主动退出** (不弹 CSAT) | CUSTOMER |
| `/bootstrap` | GET | 启动面板 (待接/已接/未读总数) | AGENT |
| `/offline/drain` | GET | 拉取离线消息 (WebSocket 失联期间堆积) | 任意 |
| `/{id}/unread` | GET | 该会话的未读数 | 任意 |
| `/agent/status?status=` | POST | 切换坐席状态 (ONLINE/BUSY/AWAY) | AGENT |
| `/agent/status` | GET | 查询当前坐席状态 | AGENT |

### 2.1 智能分配坐席 (`POST /api/im/session/create`)

**链路:**
```
Customer.vue → startSession()
  ↓ imApi.createSession(skill)
  ↓ cs-im SessionService.create()
     ├─ 查 Redis 在线坐席集 chat:agent:online
     ├─ 调 PresenceService.pickAgent(skill, isBusy)
     │   ├─ 同技能优先 (skill_tags LIKE 匹配)
     │   ├─ 排除 BUSY/AWAY 状态
     │   └─ 排除已有未关闭会话的 (用 chat:agent:session:{id} 查)
     ├─ 选定 agentId → DB 写 chat_session (status=ACTIVE)
     ├─ Redis 维护 chat:agent:session:{agentId}=sessionId
     ├─ 推系统消息 "客服 XXX 已为您服务 (擅长: skill)"
     │   ↓
     │   wsPushService.pushToUser (Redis pub/sub chat:ws:push:{userId})
     │     ↓
     │   WsPushService.onMessage 本实例 SimpMessagingTemplate.convertAndSendToUser
     │     ↓
     │   STOMP 客户端 /user/queue/messages 收到
     └─ 返回 ChatSession 对象
```

### 2.2 客户主动退出 (`POST /api/im/session/{id}/exit`)

**链路:**
```
Customer.vue → customerExit() (弹 confirm 二次确认)
  ↓ imApi.customerExit(id, reason)
  ↓ cs-im SessionService.customerExit()
     ├─ 校验 role=CUSTOMER && session.customerId == uid
     ├─ 幂等检查 (status=CLOSED 直接 return 200)
     ├─ DB: status=CLOSED, closedAt=now, lastMessage="客户主动退出"
     ├─ Redis: 删 chat:agent:session:{agentId} + chat:customer:session:{customerId}
     ├─ sendSystemMessage("客户已主动退出 (reason)") → 双方 /queue/messages
     ├─ wsPushService.notifySessionClosed (type=CLOSED) → 双方 /queue/events
     └─ auditLog: action=CUSTOMER_EXIT, target=sessionId, detail=agentId+reason
  ↓
Customer.vue onEvent(CLOSED) → stopRecorder('SESSION_CLOSED') + 清 session 状态
Agent.vue     onEvent(CLOSED) → 从列表移除 + 清未读 + ElMessage "客户已主动退出"
```

### 2.3 客户申请转接 (`POST /api/im/session/{id}/request-transfer`)

**链路:**
```
Customer.vue → requestTransfer() (弹 prompt 输入原因)
  ↓ imApi.requestTransfer(id, skill)
  ↓ cs-im SessionService.customerRequestTransfer()
     ├─ 校验 role=CUSTOMER
     ├─ 同技能可分配坐席: SELECT * FROM user WHERE role='AGENT' AND status=1 AND skill_tags LIKE '%"tech"%' ORDER BY id
     ├─ 排除当前 agentId, 取第一个
     ├─ 无同技能时: 任意可分配坐席
     ├─ 全部失败 → 503 错误
     ├─ 更新 session.agentId, transferredFromAgentId, transferReason="客户申请转接"
     ├─ 重新 assignAgent (Redis 映射)
     ├─ sendSystemMessage("客户申请转接, 客服已更换为 ...")
     ├─ notifySessionTransferred (type=TRANSFERRED)
     └─ auditLog: action=CUSTOMER_TRANSFER, detail=from=+to=+skill=
  ↓
Customer.vue onEvent(TRANSFERRED) → refreshSessionFromServer (拉新 agentId)
Agent.vue     onEvent(TRANSFERRED) → refreshSessions (列表刷新)
```

### 2.4 坐席转接 (`POST /api/im/session/{id}/transfer`) — 区别于客户转接

坐席主动转接到指定坐席 (指定 toAgentId), 客户转接是系统自动选择。两者的 WebSocket 事件都是 `TRANSFERRED`, 推送目标相同。

---

## 3. 消息模块 (cs-im `/api/im/message/**` + WebSocket)

### 3.1 REST 接口

| 接口 | 方法 | 含义 |
|---|---|---|
| `/api/im/session/{id}/messages?limit=` | GET | 历史消息分页 |
| `/api/im/session/message/{msgId}/recall` | POST | 撤回一条消息 (2 分钟内) |
| `/api/im/session/message/{msgId}/read` | POST | 单条已读回执 |
| `/api/im/session/{id}/read-all` | POST | 整会话已读 |

### 3.2 WebSocket STOMP 协议

#### 3.2.1 端点

| Endpoint | 用途 | 携带 token |
|---|---|---|
| `/ws/customer` | 客户连接 | `?token=xxx` (浏览器 WS header 限制) |
| `/ws/agent` | 坐席连接 | 同上 |
| `/ws/admin` | 管理员连接 | 同上 |

**Handshake 拦截链** (`HandshakeAuthInterceptor`):
```
HTTP Upgrade WebSocket
  ↓ 提取 token: header Authorization → query ?token=
  ↓ JwtUtil.parse(secret) → uid/role/nickname
  ↓ 写入 ServerHttpRequest.attributes (供 STOMP CONNECT 帧用)
  ↓ WS upgrade 成功
```

**STOMP CONNECT 帧** (`StompAuthChannelInterceptor.preSend`):
```
客户端发 CONNECT
  ↓ preSend 拦截
  ↓ 从 SessionAttributes 取 user 信息, 实例化 StompPrincipal
  ↓ accessor.setUser(principal)
  ↓ presenceService.online(uid, role)  ← 注册到 Redis + 本地 Set
  ↓ 首次注册 → 发布 PresenceChangedEvent
  ↓ WsPushService.@EventListener → 查活跃会话 → 推对端 PRESENCE=ON
  ↓ 转发到 broker
  ↓ 服务端回 CONNECTED 帧
```

**STOMP DISCONNECT 帧**:
```
  ↓ preSend 拦截, 拿 principal
  ↓ presenceService.offline(uid, role)
  ↓ localRegistered.remove(uid) → 发布 PresenceChangedEvent(online=false)
  ↓ 推对端 PRESENCE=OFF
```

#### 3.2.2 订阅目的地 (Destination)

| Destination | 类型 | 谁订阅 | 内容 |
|---|---|---|---|
| `/user/queue/messages` | user-destination | 登录后的所有用户 | 聊天消息推送 (MessageDTO) |
| `/user/queue/events` | user-destination | 同上 | 事件 (READ/RECALL/TRANSFERRED/CLOSED/PRESENCE) |
| `/topic/sessions/new` | topic | 坐席 | 新会话等待通知 (NEW_WAITING) |
| `/topic/typing/{sessionId}` | topic | 会话双方 | 输入状态 (TYPING) |

#### 3.2.3 应用层 `@MessageMapping`

| 路径 | 处理方法 | 入参 | 行为 |
|---|---|---|---|
| `/app/send/{sessionId}` | `MessageController.send` | MessageDTO + Principal | 存消息 + 推双方 (peer 推 /queue/messages, sender 推 echo) |
| `/app/typing/{sessionId}` | `MessageController.typing` | `{typing: true/false}` | 推 /topic/typing/{sessionId} 给会话旁观方 |

**消息发送链路 (核心)**:
```
Customer 输入消息
  ↓ Customer.vue send()
  ↓ stomp.publish('/app/send/'+sessionId, {sessionId, msgType, content})
  ↓ STOMP SEND 帧到 cs-gateway → cs-im
  ↓ MessageController.send()
     ├─ @DestinationVariable sessionId 提取 (URL 参数)
     ├─ MessageService.handleIncoming()
     │  ├─ DB 写入 chat_message
     │  ├─ session.lastMessage 同步更新
     │  ├─ 推发送者 echo: convertAndSendToUser(senderId, "/queue/messages", dto)
     │  ├─ 推对端 (peer):
     │  │   ├─ unreadCounterService.incr(peerId, sessionId) (Redis HASH)
     │  │   ├─ if presenceService.isOnline(peerId):
     │  │   │     convertAndSendToUser(peerId, "/queue/messages", dto)  ← 实时推送
     │  │   └─ else:
     │  │         offlineStore.push(peerId, dto)  ← 离线存储
     └─ 客户端收到 frame, onIncomingMessage 回调 appendMessage
```

**接收端渲染 (Customer.vue)**:
```
ws-client.js._doSubscribe callback (raw STOMP frame)
  ↓ try JSON.parse(msg.body)
  ↓ onIncomingMessage(msg)
     ├─ if session.value && msg.sessionId && 不匹配 → 丢弃 (避免异会话污染)
     ├─ appendMessage(msg) → messages.value.push(msg) → Vue 反应式触发 DOM 更新
     └─ if 不是我发的 → imApi.readMessage(msg.id) 上报已读
```

---

## 4. 客服状态机

```
          ┌─────────┐  抢单   ┌─────────┐
   start →│ WAITING │────────→│ ACTIVE  │ ←──────┐
          └────┬────┘         └────┬────┘        │
               │ 客户退出          │ 转接         │ 转接
               ↓                   ↓             │
          ┌─────────┐         ┌─────────┐        │
          │ CLOSED  │←────────│ ACTIVE  │────────┘
          └─────────┘         └─────────┘
                                ↓
                          评分 (rating 1-5)
                          写回 chat_session 表
```

**坐席在线状态** (Redis key):
- `chat:agent:online` (Set) — 在线坐席 id 集合
- `chat:agent:status:{id}` (String) — ONLINE/BUSY/AWAY, TTL=30min
- `chat:agent:session:{id}` (String) — 坐席当前会话 id, 用于 isBusy 判断
- `chat:user:online:{id}` (String) — 全员在线标记, TTL=30min

---

## 5. 在线状态实时同步 (PresenceService) — v2.0.1 新增

### 5.1 数据流

```
用户上线 (WS CONNECT)
  ↓ PresenceService.online(uid, role)
  ├─ localRegistered.add(uid)  ← 本机实例追踪
  │   首次 true (广播) / 重连 false (静默)
  ├─ Redis SET chat:user:online:{uid} = 1 (TTL=30min)
  ├─ if AGENT:
  │   ├─ SADD chat:agent:online {uid}
  │   └─ 首次: AgentStatusService.setStatus(uid, ONLINE)
  └─ 首次 → eventPublisher.publishEvent(PresenceChangedEvent online=true)

事件发布后:
  ↓ @EventListener WsPushService.onPresenceChanged
  ↓ 查询该 uid 所有活跃会话 (status IN WAITING/ACTIVE)
  ↓ 对每个 peer (客户/坐席): pushToUser(peerId, {type:'PRESENCE', online:true, ...})
```

**用户下线** (WS DISCONNECT):
```
PresenceService.offline(uid, role)
  ├─ localRegistered.remove(uid)  ← 拿到返回值判断是否曾经在线
  ├─ Redis DEL chat:user:online:{uid}
  ├─ if AGENT: SREM chat:agent:online, DEL status key
  └─ 曾经在线 → 广播 PRESENCE=OFF
```

### 5.2 循环依赖的解法

PresenceService → WsPushService → SimpMessagingTemplate → WebSocketConfig → StompAuthChannelInterceptor → PresenceService

用 **Spring ApplicationEvent** 解耦: PresenceService 只发事件, 不依赖 WsPushService。

### 5.3 前端渲染

**Agent.vue**:
```
onEvent({type:'PRESENCE', sessionId, online, ts})
  ├─ 在 sessions[] 找对应 sessionId, 更新 peerOnline + lastSeen
  ├─ 如果是当前会话 current, 也更新 current.peerOnline
  └─ UI 反应式触发 → 会话列表小圆点变绿/灰, 顶部 "客户已离线" tag
```

**初值** (避免刷新时空白): `GET /api/im/session/mine` 返回 `SessionView.peerOnline` (后端查 Redis)。

---

## 6. 录制回溯 (cs-im `/api/im/record/**`)

### 6.1 数据模型

```sql
chat_record (
  id, session_id, user_id, user_role,
  started_at, ended_at, end_reason,    -- NORMAL/USER_STOP/PAGE_CLOSE/PROCESS_KILLED/ERROR
  chunk_count, total_bytes,
  consent_given,                       -- 合规: 是否获用户明示同意
  created_at
)

chat_record_chunk (
  id, record_id, sequence_no,          -- 分片序号 (从 0 开始)
  mime_type ('video/webm'),
  duration_ms, byte_size,
  storage_path,                        -- <root>/<recordId>/<seq>-<uuid>.webm
  uploaded_at
)

chat_audit_log (id, actor_id, actor_role, action, target, detail, ip, user_agent, created_at)
```

### 6.2 REST 接口

| 接口 | 方法 | 含义 | 权限 |
|---|---|---|---|
| `/init?sessionId=&consent=` | POST | 开启录制 (consent 必须 true) | 任意已登录 |
| `/chunk?recordId=&sequenceNo=&durationMs=` | POST | 上传一个分片 (multipart) | 仅 record.userId |
| `/end?recordId=&endReason=` | POST | 结束录制 | 仅 record.userId |
| `/session/{id}` | GET | 列出会话所有录像 | 任意已登录 |
| `/session/{id}/with-chunks` | GET | 同上 + 每个录像的分片列表 (一次拉完) | **三道权限** (本人/该会话坐席/admin) |
| `/{id}/chunks` | GET | 录像的所有分片 | 任意已登录 |
| `/chunk/{id}/raw` | GET | 下载一个分片的二进制 (回放用) | **三道权限** |

### 6.3 前端 SDK — `record-sdk.js`

#### 6.3.1 架构

```
ChatRecordSDK
  ├─ opts: apiBase, token, sessionId, userId, nickname, target, fps, chunkDurationMs, bitrate, watermark
  ├─ 内部: html2canvas 周期捕获 DOM → offscreen canvas → MediaRecorder → WebM blob
  └─ 上传: FormData multipart → /api/im/record/chunk (失败重试 3 次)
```

#### 6.3.2 录制流程

```
Customer.vue tryRecorder() (用户已同意)
  ↓ new ChatRecordSDK({...})
  ↓ sdk.start()
     ├─ 1. 后端 init (consent=true)
     │     ↓ chat_record INSERT (consentGiven=true)
     │     ↓ audit_log: RECORD_INIT
     │     → 返回 recordId
     ├─ 2. 创建 offscreen canvas (限制 1280x800)
     ├─ 3. setInterval 1000/fps ms → html2canvas(target) → drawImage 到 canvas
     │     (失败一帧不中断, 只 warn)
     │     if watermark: _drawWatermark(ctx)
     │       ├─ 顶部红条: "本会话已开启录制 — 用于服务回溯"
     │       └─ 右下角: 时间戳 / 录像#ID / 会话#ID / 用户#ID
     ├─ 4. canvas.captureStream(fps) → MediaRecorder (WebM VP9/VP8, 500kbps)
     ├─ 5. recorder.start(5000)  ← 每 5s 一个分片
     │     ondataavailable(blob):
     │       ├─ chunkQueue.push({blob, sequence, duration, uploaded:false})
     │       └─ uploadPromise.then(() => _uploadOne(blob, sequence))
     │           (串行上传, 保证顺序)
     └─ 6. 挂 visible indicator + 监听 beforeunload/pagehide/visibilitychange
```

#### 6.3.3 异常容错

| 场景 | 处理 |
|---|---|
| **客户主动退出** | `Customer.customerExit()` → `stopRecorder('USER_STOP')` → 等所有分片上传 → `/end?endReason=USER_STOP` |
| **客户切换页面前** | `beforeunload`/`pagehide` 事件 → `_onUnload()` → `navigator.sendBeacon()` 把剩余分片 + end 一次性同步发出 |
| **手机切后台** | `visibilitychange=hidden` → 同上 sendBeacon 兜底 |
| **杀进程** | best-effort: in-memory 未上传分片丢失, 但周期性上传 (5s/段) 保证多数内容已落库。audit_log 留痕 |
| **网络抖动** | `_uploadOne` 失败重试 3 次, 指数退避 500ms→1s→1.5s |
| **html2canvas 单帧失败** | warn + 跳过, 继续录制 |

#### 6.3.4 静默模式 (低摩擦版) — vs 标准模式

| 维度 | 标准模式 | 静默模式 (低摩擦) |
|---|---|---|
| 同意弹窗 | ✅ ElMessageBox 强制 | ❌ 移除弹窗 |
| 可见指示器 | ✅ 右上角红点 + 脉冲动画 | ✅ footer 一行 8px 灰字 "本次会话已开启合规录制" |
| 用户首次告知 | 弹窗 | 隐私政策 / ToS / 注册页勾选 |
| 录制过程打断 | 弹窗中断输入流 | 零中断 |
| 合规依据 | 主动同意 (consent) | 合法利益 / 上位法授权 + 被动告知 |

**切换**: `new ChatRecordSDK({ silentMode: true })` 即进入静默模式 (SDK 内部跳过 `onConsentRequired`, 改用低强度 indicator)。后端不变 (consent=true 仍要传, 但前端不再弹窗)。

> ⚠️ 完全无指示 + 无告知 = 偷拍, 过不了任何隐私法审计 (PIPL/GDPR/CCPA)。**真·静默只能在上位法明确豁免的场景下做** (如金融监管要求强制录音且客户开户协议明示)。

### 6.4 回放页 (`/replay/:sessionId`)

#### 6.4.1 数据加载

```
Replay.vue onMounted
  ↓ recordApi.sessionRecords(sessionId)
  ↓ GET /api/im/record/session/{id}/with-chunks
  ↓ 返回 {records: ChatRecord[], chunks: Record<Long, ChatRecordChunk[]>}

用户点击录像
  ↓ rebuildBlob()
     ├─ recordApi.recordChunks(recordId) → 按 sequenceNo 排序
     ├─ Promise.all(chunks.map(downloadChunkBlob))
     │   ↓ GET /api/im/record/chunk/{id}/raw (axios blobHttp, responseType=blob)
     ├─ new Blob(blobs, {type: 'video/webm'})
     └─ URL.createObjectURL(merged) → <video :src=videoSrc autoplay>
```

**下载完整录像**: 同上合并 Blob → `<a download="record-{id}-{ts}.webm">`

#### 6.4.2 WebM 直拼的限制

WebM 是 EBML 容器, 多段直拼只能保证**首段可正常播放**; 后续分段要无损拼接需要 ffmpeg remux (生产建议增加一个 `/merged` 流式接口做服务端 ffmpeg 合流)。当前实现满足基本回溯需求, 想要无缝播放可改用 **MediaSource Extensions (MSE)** 按 sequenceNo 动态 `appendBuffer`。

---

## 7. 消息可靠投递

### 7.1 三层防线 (离线消息 + 重连补漏 + 在线推送)

```
┌─────────────────────────────────────────────────────────┐
│ L1 在线推送: WS /queue/messages 即时到达                   │
│     ↓ (WS 断线时, 这条会丢)                                │
│ L2 离线存储: presenceService.isOnline=false → Redis List   │
│   chat:offline:msg:{userId} LPUSH (LTRIM 200, TTL 24h)  │
│     ↓ (重连后未拉, 这条会丢)                                │
│ L3 重连补漏: ws-client.js onReconnected → loadHistory()   │
│   从 DB 拉最近消息, 兜底断网期间丢失的推送                   │
└─────────────────────────────────────────────────────────┘
```

### 7.2 `loadHistory` vs `drainOffline`

| 路径 | 触发 | 数据来源 |
|---|---|---|
| `GET /api/im/session/{id}/messages?limit=100` | onReconnected, 组件 mount | MySQL `chat_message` 表 (权威, 不丢) |
| `GET /api/im/session/offline/drain` | 组件 mount 时 (一次性) | Redis List (实时, 但可能 LTRIM 截断) |

**策略**: mount 时同时拉两个, 先 history 再 merge offline (按 id 去重)。reconnect 时只拉 history。

---

## 8. 前端 STOMP 客户端 (`ws-client.js`)

### 8.1 关键设计

```js
class StompClient {
  subscribe(dest, cb)  // 幂等: 相同 (dest, cb) 只订阅一次
  connect(path)        // 实际调 @stomp/stompjs v7 的 client.activate()
                        // path: '/ws/customer' 或 '/ws/agent'
}
```

**幂等订阅** 解决 v2.0.0 发现的 STOMP 消息双发 bug:
```
[bug] UI 在 onConnected 里调 subscribe + onConnect 内部也对 subscribed Set 做 forEach
      → 同一 destination 被订阅 2 次 → 每个 STOMP frame 触发回调 2 次 → 消息双发
[fix] 
  1. subscribe() 幂等: 相同 (dest, cb) 跳过
  2. onConnected 只在首次触发 (用 _firstConnected 标记), 状态更新钩子, 不做订阅
  3. 重连后从 subscribed Set 自动重订, UI 不需操心
  4. UI 的订阅挪到 connect() 之前
```

### 8.2 重连机制

```
WS 断开 (网络抖动/锁屏/杀进程)
  ↓ onWebSocketClose 触发
  ↓ _intentionallyClosed=false → 指数退避
  ↓ reconnectDelay = min(32s, 1000 * 2^reconnectAttempt)
  ↓ 重新建立连接 → onConnect 再次触发
  ↓ subscribed.forEach → 重新订阅所有目的地
  ↓ onReconnected 回调 (区别于首次 onConnected)
     ↓ Customer.vue → loadHistory (补漏)
     ↓ Agent.vue     → refreshSessions + readAll
```

---

## 9. 错误码约定

| code | 含义 | 前端处理 |
|---|---|---|
| 0 | 成功 | 正常 |
| 401 | 未登录 / token 失效 | 清 userStore, 跳 /login |
| 403 | 无权 (越权访问) | ElMessage.error, 不重定向 |
| 404 | 资源不存在 | ElMessage.error |
| 409 | 状态冲突 (重复操作/已关闭) | 幂等返回, UI 提示 "已处理" |
| 500 | 内部错误 | ElMessage.error, 上报 |

---

## 10. 关键时序图

### 10.1 客户接入会话完整流程

```
客户            Customer.vue         cs-gateway        cs-im            Redis          MySQL
 │                  │                    │              │                │              │
 │─[点开始咨询]───→│                    │              │                │              │
 │                  │─[POST /create]──→ │─forward──→   │                │              │
 │                  │                    │              │─pickAgent(skill)─→ │        │
 │                  │                    │              │              online agents  │
 │                  │                    │              │   (skill_tags LIKE + status=1)│
 │                  │                    │              │                │              │
 │                  │                    │              │─INSERT session ─────────────────→│
 │                  │                    │              │─SET agent:session:4=2 ─→│      │
 │                  │                    │              │─sendSystemMessage ────→│       │
 │                  │                    │              │  pushToUser(4)  ───→   │       │
 │                  │                    │              │  pushToUser(2)  ───→   │       │
 │                  │                    │◀─200 OK──────│                │              │
 │◀─会话对象───────│                    │              │                │              │
 │                  │─[WS connect /ws/customer?token=xxx]─→ │            │              │
 │                  │                    │              │─StompAuthChannelInterceptor  │
 │                  │                    │              │─presenceService.online(uid)  │
 │                  │                    │              │  SET user:online:2 ──→│       │
 │                  │                    │              │  publishEvent PRESENCE=ON    │
 │                  │                    │              │  @EventListener 推对端 (坐席) │
 │                  │                    │              │←CONNECTED frame                │
 │                  │◀─[STOMP /queue/messages: 系统消息]──│                │              │
 │─UI 渲染系统消息─│                    │              │                │              │
```

### 10.2 坐席断网 → 重连 → 补漏

```
坐席            Agent.vue       ws-client.js           cs-im           Redis
 │                │                │                    │                │
 │─[网络断开]───→│                │                    │                │
 │                │                │[onWebSocketClose]  │                │
 │                │                │  reconnectDelay = 1s → 2s → 4s    │
 │                │                │[client.activate]   │                │
 │                │                │                    │                │
 │                │                │  [WS connect OK]   │                │
 │                │                │[onConnect]         │                │
 │                │                │  forEach subscribed Set 重订         │
 │                │                │  [_firstConnected=true 已, 不触发]  │
 │                │                │  [onReconnected]   │                │
 │                │                │  refreshSessions() │                │
 │                │                │  readAll(currentId)│                │
```

---

## 11. 部署清单

| 组件 | 端口 | 启动顺序 | 关键配置 |
|---|---|---|---|
| MariaDB | 3306 | 1 | `online_chat` 库, root/951127, schema 见 `chat_record.sql` |
| Redis | 6379 | 1 | 无密码, 用于 presence/ws pubsub/offline store |
| cs-auth | 9001 | 2 | `JWT_SECRET` 必须三服务一致 (cs-auth/cs-gateway/cs-im) |
| cs-im | 9002 | 3 | `record.storage-path=/tmp/chat-records`, `multipart.max-file-size=50MB` |
| cs-gateway | 9000 | 4 | 静态路由 `/auth/**` `/api/im/**` `/ws/**` |
| Vite dev | 5173 | 5 | `host: '0.0.0.0'`, proxy `/auth /api /ws → localhost:9000` |

**生产部署要点**:
- 用 nginx 替代 Vite (静态文件 + 反代 /api/im /ws 到 cs-gateway)
- nginx 配置 WebSocket 升级: `proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";`
- JWT secret 用环境变量, 不要硬编码
- chat_record 文件存储换成对象存储 (S3/MinIO) 或配 ffmpeg 合流
- 30 天 cron 清理过期录像: `DELETE FROM chat_record WHERE ended_at < NOW() - 30 DAY`

---

## 12. 待优化 / 已知限制

| 项 | 现状 | 改进方向 |
|---|---|---|
| WebM 直拼回放 | 首段可播, 后续分段可能掉帧 | MSE 流式 appendBuffer 或服务端 ffmpeg remux |
| 录制体积 | 500kbps × 1h ≈ 220MB | 客户用 200kbps, 坐席用 500kbps 分档 |
| 录制过期清理 | 仅标 ended_at, 无 cron | 加 @Scheduled 任务 |
| 客户录像重连断点 | 进程被杀时丢分片 | IndexedDB 暂存 in-memory 分片 |
| 客户转接无新会话 | in-place 切换 agent, 历史保留 | 已经做了, 但 skill 切换能力有限 |
| 多坐席场景下用户感知 | 只能从头像判断换了客服 | 加 "上一位客服" 字段展示 |

---

> 文档版本: v1.0 · 2026-07-07 · 对应代码 commit `cae36ef`