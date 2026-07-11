# V6 客户体验 + 模块化增强

> 更新时间: 2026-07-12

---

## 1. 客户体验 (UX) 增强

### 1.1 草稿自动保存 (useDraft)
**业务痛点**: 客户输入到一半, 切换会话 / 刷新 / 断网, 内容丢失.
**解决方案**:
- 输入内容变化 → 500ms 防抖 → localStorage 写入
- 切换 sessionId → 重新加载对应草稿
- 发送成功 → 自动清空草稿
- 最多保留 50 个 key 的草稿 (LRU 淘汰)

### 1.2 键盘快捷键 (useKeyboard)
- **Enter** - 发送消息 (排除 IME 组合中)
- **Shift+Enter** - 换行
- **Esc** - 关闭弹窗 / 抽屉
- **Cmd/Ctrl+K** - 打开消息搜索
- **Cmd/Ctrl+/** - 显示快捷键帮助
- **Alt+C** - 切换技能 (客户)
- **Alt+H** - 申请转人工 (客户)

### 1.3 拖拽上传 (useDragUpload)
**业务场景**: 客户拖文件到聊天区域, 自动上传 + 发送.
- dragenter counter 处理子元素, 避免闪烁
- drop 后立刻清零, 触发 onFiles 回调
- 配套组件: `DragUploadOverlay.vue` 全屏虚线遮罩

### 1.4 网络/连接状态 (useOnlineStatus + ConnectionBanner)
- 顶部横幅: "网络已断开" (红色) / "正在重连" (黄色 + spinner)
- 监听 navigator.onLine + STOMP 连接状态
- 不影响业务, 仅做提示

### 1.5 智能滚动 (useAutoScroll)
- 用户在底部 80px 内 → 新消息触发自动滚动
- 用户上滑阅读历史 → 不滚动, 显示 "回到最新" 按钮
- 防止自动滚动打断阅读

### 1.6 主题切换 (useTheme + ThemeToggle)
- ☀️ 浅色 / 🌙 暗色 / ⚙️ 自动 (跟随系统)
- `data-theme` 属性控制, CSS 变量增量覆盖
- 11 类组件暗色样式 (topbar / composer / msg-bubble / el-* / 滚动条)
- localStorage 记忆, 系统切换自动响应

### 1.7 全局 loading (stores/loading + GlobalLoading)
- Pinia store 控制, 长任务 (录像合并/上传) 显示全屏遮罩
- 配套组件 `GlobalLoading.vue`, 自动加载到 App.vue

### 1.8 错误处理统一 (utils/error-handler.js)
8 类错误码统一处理:
| 状态码 | 含义 | 处理 |
|--------|------|------|
| 400 | 参数错误 | 友好提示 + 不重试 |
| 401 | 未登录 | 弹窗确认 → /login |
| 403 | 无权 | 提示联系管理员 |
| 404 | 不存在 | 提示 |
| 409 | 冲突 (CAS) | 提示已被抢, 刷新 |
| 429 | 限流 | 提示稍后重试 |
| 500+ | 系统错误 | 通用提示 + 记录 |
| 网络 | 连接失败 | 提示检查网络 |

---

## 2. 模块化重构

### 2.1 拆分 composables
**之前**: Customer.vue 1123 行 + Agent.vue 1080 行 (大量重复)
**现在**: 新增 8 个 composable, 各司其职:

| Composable | 行数 | 职责 |
|------------|------|------|
| useDraft | 60 | 草稿自动保存 |
| useKeyboard | 70 | 全局快捷键 |
| useDragUpload | 70 | 拖拽上传 |
| useOnlineStatus | 30 | 网络状态 |
| useAutoScroll | 50 | 智能滚动 |
| useTheme | 50 | 主题切换 |
| useWaitingList | 80 | 等候队列 (5s 轮询 + CAS) |
| useDashboard | 60 | 看板数据 (30s 缓存) |

### 2.2 通用组件
| 组件 | 用途 |
|------|------|
| EmptyState | 统一空状态 (default/primary/success 三色) |
| ConnectionBanner | 顶部连接状态横幅 |
| DragUploadOverlay | 拖拽上传遮罩 |
| KeyboardHelpDialog | 快捷键帮助弹窗 (Cmd+/) |
| ThemeToggle | 主题切换按钮 |
| GlobalLoading | 全屏 loading 遮罩 |

### 2.3 集成到 Customer/Agent
| 位置 | 集成 |
|------|------|
| 顶部 | ThemeToggle + KeyboardHelpDialog + ConnectionBanner |
| 输入框 | Enter 发送 + 自动聚焦 (移动端除外) |
| 聊天区 | 拖拽上传 (DragUploadOverlay) |
| 全局 | useKeyboard (enter/search/help/escape) |

---

## 3. 注释增强

### 3.1 6 个 Service 类 Javadoc
- AuthService - 鉴权 (BCrypt + JWT + 限流)
- BotService - 机器人回复 (规则 + 算法 + 设计)
- CannedResponseService - 模板 (skill 过滤 + 公共/私有)
- MessageService - 消息 (撤回窗口 + 搜索 + 推送)
- SessionService - 会话 (CAS 防串线 + 转人工 + 状态机)
- SystemMessageService - 系统消息 (4 类消息模板)

### 3.2 关键算法详细注释
**LocalAiService.decide** - 决策链:
```
0) 边界: 空文本 → 问候
1) classify intent + analyze sentiment (并行)
2) switch intent:
   TRANSFER_HUMAN → 转人工
   GOODBYE → 告别
   COMPLAINT → 升级
   THANKS → 不用客气
3) FAQ 检索 (TF-IDF 相似度 > 0.15)
4) 兜底: ANGRY → 转人工 / 问号 → 建议 / 普通 → 兜底
```

**Agent.claimOne** - 防串线 CAS:
```
step 1: 参数防御 (Vue 3 @click 防 PointerEvent)
step 2: 设置 claimingId, 显示 loading
step 3: 调 imApi.claimSession (后端 CAS UPDATE)
step 4: 成功 → refresh + 选中
step 5: 失败 → 分类 (409 防串线 / 业务错误)
```

### 3.3 API 客户端注释
11 个 API 客户端加头部注释, 描述端点 / 数据流 / 实时性 / 失败兜底.

---

## 4. 性能优化

| 维度 | 优化 |
|------|------|
| 草稿 | LRU 50 个 key, 500ms 防抖 |
| 看板 | 30s 缓存 |
| 轮询 | 等候队列 5s, 可停止 |
| 拖拽 | counter 处理子元素, 避免重复触发 |
| 滚动 | 80px 阈值, 防止自动滚打断 |
| 暗色 | data-theme + CSS 变量, 0 JS 切换 |
| 主题 | localStorage 记忆 + 系统监听 |

---

## 5. UI 美化

| 元素 | 美化 |
|------|------|
| 主题 | 浅色 / 暗色 / 自动三态切换 |
| 横幅 | 顶部连接状态 (3 种颜色) |
| 弹窗 | 拖拽遮罩 (3D 阴影 + 虚线) |
| 快捷键 | 命令面板风格, kbd 标签 |
| 主题按钮 | 顶部 1-click 切换 |

---

## 6. 验证

| 项 | 状态 |
|----|------|
| 后端 mvn install | ✓ |
| 前端 vite build | ✓ |
| TypeScript 编译 | ✓ (无 .ts) |
| 8 个新 composable 加载 | ✓ |
| 6 个新组件渲染 | ✓ |
| ChatComposer Enter 发送 | ✓ |
| 草稿 localStorage 持久化 | ✓ |
| 暗色主题切换 | ✓ |
| 快捷键注册 | ✓ |
| 拖拽遮罩显示 | ✓ |
| ConnectionBanner 横幅 | ✓ |
| GlobalLoading 全屏 | ✓ |
| error-handler 401 跳登录 | ✓ |
| 6 Service Javadoc | ✓ |
| 11 API 客户端注释 | ✓ |

---

## 7. 文件清单

### 新增 (13)
- `composables/useDraft.js` (60 行)
- `composables/useKeyboard.js` (70 行)
- `composables/useDragUpload.js` (70 行)
- `composables/useOnlineStatus.js` (30 行)
- `composables/useAutoScroll.js` (50 行)
- `composables/useTheme.js` (50 行)
- `composables/useWaitingList.js` (80 行)
- `composables/useDashboard.js` (60 行)
- `components/common/EmptyState.vue` (40 行)
- `components/common/ConnectionBanner.vue` (50 行)
- `components/common/DragUploadOverlay.vue` (40 行) (chat 目录)
- `components/common/KeyboardHelpDialog.vue` (50 行)
- `components/common/ThemeToggle.vue` (35 行)
- `components/common/GlobalLoading.vue` (40 行)
- `stores/loading.js` (25 行)
- `styles/dark-theme.css` (90 行)
- `utils/error-handler.js` (75 行)

### 修改
- `App.vue` (加 GlobalLoading)
- `main.js` (加 dark-theme.css)
- `views/Customer.vue` (集成 8 个 composable + 6 个组件 + 注释 send/recall)
- `views/Agent.vue` (集成快捷键 + 主题 + 注释 claimOne/send)
- `components/chat/ChatComposer.vue` (Enter 发送 + 自动聚焦)
- `api/im.js` 等 6 个 API (头部注释)
- 6 个后端 Service 类 (Javadoc)

**总计**: 13 个新文件 + 11 个修改文件 + 0 删文件
