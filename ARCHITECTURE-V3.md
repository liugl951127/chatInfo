# Online-Chat V3 架构设计 - 4 阶段 + 视频/语音 + M3 适配

> 创建时间: 2026-07-10
> 状态: 进行中
> 总工期: ~3-4 个月 (4 个阶段 + 2 个新渠道)

---

## 1. 总体目标

将 online-chat 从"传统 IM 客服系统"升级为"AI 驱动的智能服务平台":

| 维度 | V2.x (现状) | V3 (目标) |
|------|------------|----------|
| 客服角色 | 人工为主, Bot 辅助 | AI 主导 + 人工监督 |
| 客户体验 | 响应式 (等客户问) | 预见式 (AI 主动关怀) |
| 客户认知 | 单次会话 | 360° 数字孪生 |
| 服务渠道 | 文字 IM | 文字 + 视频 + 语音 + 社区 |
| AI 能力 | 关键词匹配 | MiniMax-M3 (兼容) / Java 自研 AI 多模态理解 + 决策 + 生成 |

---

## 2. 模块架构 (4 新模块 + 2 新渠道)

```
backend/
├── cs-im                  现有 IM 服务 (文字消息)
├── cs-auth                现有鉴权
├── cs-gateway             现有网关
├── cs-common              现有公共库
│
├── cs-prediction   [新]   预见式服务 (主动关怀)
├── cs-cdp         [新]   数字孪生 360 (客户画像)
├── cs-customer-success [新]  客户成功体系
├── cs-community   [新]   群体智能社区
├── cs-video       [新]   在线视频会话 (WebRTC)
└── cs-voice       [新]   智能语音电话 (AI Phone)
```

### 2.1 cs-prediction (预见式服务)

**职责**：在客户有问题之前就主动解决

**核心场景**：
- 订单异常检测 (物流停滞 24h, 支付失败 3 次)
- 流失预警 (行为减少 + 情绪下滑)
- 价值客户关怀 (生日/重要节点/沉默唤醒)
- 主动发起会话 (AI 主动说"检测到您可能...")

**技术栈**：
- 行为埋点 SDK (前端) → Redis Stream → Flink / 自研滚动窗口
- 异常检测: 规则引擎 + 简单统计
- AI 决策: MiniMax-M3 (兼容) / Java 自研 AI 推理
- 主动触达: STOMP 推送 / 短信 / Push

**数据流**：
```
前端 SDK
  ↓ HTTP POST /api/cdp/event
cs-cdp (事件采集)
  ↓ Redis Stream: cdp:events
cs-prediction (规则引擎 + M3 推理)
  ↓ STOMP /user/{uid}/queue/events
前端 AI 推送卡片
```

### 2.2 cs-cdp (数字孪生 360)

**职责**：给每个客户一个完整的故事

**数据模型**：
```sql
CREATE TABLE cdp_customer_profile (
  user_id BIGINT PRIMARY KEY,
  nickname VARCHAR(64),
  avatar_url VARCHAR(255),
  vip_level TINYINT DEFAULT 0,           -- 0=普通, 1=银, 2=金, 3=钻石
  register_at DATETIME,
  last_active_at DATETIME,
  total_orders INT DEFAULT 0,
  total_amount DECIMAL(12,2) DEFAULT 0,
  avg_csat DECIMAL(2,1) DEFAULT 0,       -- 平均满意度
  churn_risk TINYINT DEFAULT 0,          -- 0=健康, 1=关注, 2=风险, 3=流失
  health_score INT DEFAULT 100,          -- 客户健康分
  tags JSON,                              -- 标签数组
  preferences JSON,                       -- 偏好
  updated_at DATETIME
);

CREATE TABLE cdp_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  event_type VARCHAR(64),                  -- 'page_view', 'order_paid', 'chat_start'...
  payload JSON,
  occurred_at DATETIME,
  INDEX idx_user_time (user_id, occurred_at)
);

CREATE TABLE cdp_tag (
  user_id BIGINT,
  tag_key VARCHAR(64),                    -- 'new_customer', 'silent_30d'...
  tag_value VARCHAR(255),
  computed_at DATETIME,
  PRIMARY KEY (user_id, tag_key)
);
```

**30 个基础标签 (阶段 1)**：
- 身份：new_customer / returning_customer / vip_silver / vip_gold / vip_diamond
- 活跃：active_7d / active_30d / silent_7d / silent_30d / dormant_90d
- 价值：high_value / mid_value / low_value / churned
- 行为：browse_heavy / chat_heavy / return_heavy
- 情绪：satisfied / neutral / dissatisfied
- 偏好：morning_user / evening_user / weekend_user / mobile_first
- 风险：first_time_return / frequent_complainer / payment_failed
- 行业：enterprise / individual / vip
- ...

### 2.3 cs-customer-success (客户成功)

**职责**：让客户持续成功, 不只是修问题

**核心能力**：
- 客户健康分模型 (0-100)
- 自动 onboarding 流程
- 续约预测 + 提前预警
- 客户分层：Champion / Healthy / At-Risk / Churned
- AI 客户成功经理 (自动跟进)

**健康分公式** (阶段 3)：
```
health_score = 
  0.3 * login_frequency_score   // 30%
+ 0.3 * feature_usage_score     // 30%
+ 0.2 * support_ticket_score    // 20% (越少越高)
+ 0.2 * csat_score              // 20%
```

### 2.4 cs-community (群体智能)

**职责**：让客户互相帮, 沉淀 UGC

**功能**：
- 客户社区 (发帖/回复/点赞)
- AI 主持人 (审核/归类/推荐)
- UGC 知识库 (反哺 RAG)
- 激励机制 (积分/勋章/特权)
- 专家认证 (VIP 客户当 KOL)

### 2.5 cs-video (WebRTC 视频会话)

**职责**：1v1 / 多方视频通话

**架构**：
```
cs-video
  ├── WebRTC 信令服务器 (基于 STOMP, 复用 cs-im)
  ├── TURN/STUN (coturn 自建, 公网部署)
  ├── SFU 媒体服务器 (mediasoup 阶段 3 引入)
  ├── 屏幕共享 (getDisplayMedia, 已有)
  └── 录制 (双流 + 后端 ffmpeg 合并, 复用 RecordService)
```

**端点**：
- `POST /api/video/init` - 初始化视频会话
- `POST /api/video/{sessionId}/signal` - 转发 SDP/ICE
- `POST /api/video/{sessionId}/end` - 结束 + 合并录制
- `GET  /api/video/{sessionId}/merged` - 下载合并视频

### 2.6 cs-voice (智能语音电话)

**职责**：AI 接电话, 拟人交互

**架构**：
```
cs-voice
  ├── ASR (语音转文字) - 阿里云/讯飞/MiniMax-M3 (兼容) / Java 自研 AI audio_understand
  ├── TTS (文字转语音) - MiniMax-M3 (兼容) / Java 自研 AI synthesize_speech
  ├── AI Agent (Function Calling 通话中决策) - MiniMax-M3 (兼容) / Java 自研 AI
  ├── 通话录音 (合规)
  └── 软电话 SDK (WebRTC + sip.js, 浏览器内拨打电话)
```

**端点**：
- `POST /api/voice/init` - 初始化通话
- `POST /api/voice/{callId}/asr` - 上传语音流 → 转文字
- `POST /api/voice/{callId}/tts` - 文字 → 语音流
- `POST /api/voice/{callId}/ai-reply` - AI 决策回复
- `POST /api/voice/{callId}/end` - 结束 + 保存录音

---

## 3. MiniMax-M3 (兼容) / Java 自研 AI 适配方案

**核心思想**：把 MiniMax-M3 (兼容) / Java 自研 AI 作为"中央 AI 大脑", 替代/辅助 LLM 角色。

### 3.1 M3Capability 统一接口

```java
// cs-common 中新增
public interface M3Capability {
    String chat(List<Message> messages, List<Tool> tools);
    String embed(String text);
    float[] embedVector(String text);
    String tts(String text, VoiceConfig config);
    String asr(byte[] audio);
    String understandImage(String imageUrl, String prompt);
    String understandAudio(byte[] audio, String prompt);
    SentimentResult analyzeSentiment(String text);
}
```

### 3.2 适配矩阵

| 能力 | MiniMax-M3 (兼容) / Java 自研 AI 工具 | Java 调用 |
|------|----------------|----------|
| 文字对话 | 主对话引擎 | 直接 HTTP 调用 (本地 Python service) |
| Embedding | 文本向量化 | 内部 Python service / Redis vector |
| TTS | synthesize_speech | Python service streaming |
| ASR | listen_audio / audios_understand | Python service streaming |
| 图片理解 | image_synthesize + image_reverse_search | Python service |
| 情感分析 | 主对话 + prompt | Function Calling |
| 意图识别 | 主对话 + prompt | Function Calling |

### 3.3 部署形态

```
cs-m3-adapter/  (新)
  ├── python/   (M3 Python 客户端)
  │   ├── m3_server.py    FastAPI 封装
  │   ├── capabilities/   各种能力
  │   └── requirements.txt
  └── java/    (Java 客户端, 调 Python service)
      └── M3Adapter.java
```

**理由**：Python 调 M3 工具最直接, Java 端只调 HTTP 接口即可, 不需要 SDK.

---

## 4. 客户界面 v6 升级

### 4.1 11 个核心改动

1. **FAB 浮动操作按钮** (阶段 1) - 1 秒触达
2. **快捷问题卡片** (阶段 1) - AI 预判
3. **AI 主动推送卡片** (阶段 2) - 主动关怀
4. **手势操作** (阶段 2) - 摇一摇/双击/长按
5. **滑动操作** (阶段 1) - 左滑会话
6. **数字孪生个人中心** (阶段 2) - 360 视图
7. **个性化首页** (阶段 2) - 千人千面
8. **语音输入** (阶段 2) - 长按说话
9. **离线缓存** (阶段 2) - 弱网可用
10. **视觉焕新** (阶段 1) - 色彩/动效
11. **收藏/快捷回复** (阶段 3)

### 4.2 5 个核心原则

1. **1 秒触达**: FAB + 快捷面板, 任何功能 2 步内
2. **预判式**: AI 知道你想干啥, 直接给选项
3. **微交互**: 每个操作都有反馈
4. **个性化**: 千人千面, 数字孪生驱动
5. **零等待**: 离线缓存 + 乐观更新

---

## 5. 实施路线

### 阶段 1 (2 周) - MVP 骨架
- cs-prediction 订单异常 + 主动推送
- cs-cdp 30 标签 + 事件流 + profile API
- Customer.vue v6 (FAB + 快捷问题 + 个人中心)
- M3Capability 接口 (chat/embed/tts/asr)
- 数字孪生基础视图

### 阶段 2 (1 个月) - 核心场景
- 预见式全场景 (流失预警 + 价值关怀 + A/B)
- 数字孪生 360 完整 (200 标签 + 长期记忆)
- 客户界面 v6.5 (AI 推送 + 手势 + 个性化)
- 视频会话 P2P 1v1 (cs-video)
- 智能电话 MVP (cs-voice)

### 阶段 3 (1.5 月) - 体系化
- 客户成功 (健康分 + onboarding)
- 群体智能社区 MVP
- 视频多方会议 (SFU)
- 智能电话高级 (function calling 全场景)
- 客户界面 v7

### 阶段 4 (1 月) - 社区化
- 群体智能完整版
- 数字孪生预测
- 客户界面 v8

---

## 6. 数据库迁移

阶段 1 需要新增的表：
- cdp_customer_profile
- cdp_event
- cdp_tag
- prediction_rule
- prediction_event
- proactive_message

阶段 2-4 视情况增加。

---

## 7. 配置与基础设施

### 7.1 端口规划

| 服务 | 端口 | 备注 |
|------|------|------|
| cs-im | 8081 | 现有 |
| cs-auth | 8082 | 现有 |
| cs-gateway | 8080 | 现有 |
| cs-prediction | 8085 | 新增 |
| cs-cdp | 8086 | 新增 |
| cs-customer-success | 8087 | 新增 |
| cs-community | 8088 | 新增 |
| cs-video | 8089 | 新增 |
| cs-voice | 8090 | 新增 |
| m3-adapter | 8084 | Python service |

### 7.2 依赖

新增 Python 依赖 (m3-adapter)：
- fastapi
- uvicorn
- httpx (调 MiniMax-M3 (兼容) / Java 自研 AI)
- redis (Stream 消费)
- pydantic

新增 Java 依赖 (各服务)：
- webflux (调 m3-adapter HTTP)
- 现有 OK

### 7.3 环境变量

```env
# M3 适配
M3_ADAPTER_URL=http://localhost:8084
M3_DEFAULT_MODEL=minimax3

# 视频
TURN_SERVER_URL=turn:turn.example.com:3478
TURN_USERNAME=user
TURN_PASSWORD=pass

# 电话
ASR_PROVIDER=minimax
TTS_PROVIDER=minimax
SIP_SERVER=sip.example.com
```

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| M3 调用成本不可控 | 成本 | 阶段 1 用静态 fallback, 2-3 加 cache + 限速 |
| WebRTC 复杂度过高 | 视频延期 | 先 P2P 1v1, 多方延后 |
| ASR/TTS 准确率 | 体验 | 选成熟云服务 (阿里云/讯飞) + MiniMax-M3 (兼容) / Java 自研 AI fallback |
| 数字孪生隐私合规 | 法律 | 严格 PII 脱敏 + 用户授权 + 数据保留策略 |
| 群智能社区治理 | 内容 | AI 主持人 + 用户举报 + 关键词黑名单 |

---

## 9. 验收标准

### 阶段 1
- [ ] 订单物流停滞 24h, 客户自动收到推送
- [ ] 客户个人中心显示 30 个标签 + 健康分
- [ ] FAB 一秒触达 5 个核心操作
- [ ] 客户首页看到"主动关怀"卡片
- [ ] M3 chat/embed/tts/asr 4 个能力调通
- [ ] 端到端测试通过

### 阶段 2
- [ ] 流失预警 (行为减少) 自动触发
- [ ] 客户登录后看到个性化首页
- [ ] 1v1 视频通话可用 (P2P)
- [ ] 智能电话 MVP (拨打 + ASR + AI 回复 + TTS)
- [ ] 200 标签体系上线

### 阶段 3
- [ ] 客户健康分实时计算
- [ ] 客户社区 MVP 可发帖回复
- [ ] 视频多方会议 (3+ 人)
- [ ] 智能电话 function calling 全场景

### 阶段 4
- [ ] 群体智能激励机制
- [ ] 数字孪生预测
- [ ] 全功能上线

---

**当前进度**: 阶段 1 进行中
**最后更新**: 2026-07-10
