package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.dto.MessageDTO;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatSession;
import com.chat.im.service.AgentStatusService;
import com.chat.im.service.OfflineMessageStore;
import com.chat.im.service.PresenceService;
import com.chat.im.service.SessionService;
import com.chat.im.service.UnreadCounterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionController - 会话管理 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /create              客户创建会话 (mode=bot 走智能客服)
 *   - POST /claim?sessionId=X   坐席接单 (CAS 防串线, 指定 session 优先)
 *   - GET  /mine                当前用户的会话列表 (客户 mine / 坐席 active+waiting)
 *   - GET  /queue/waiting       坐席视角的等待队列 (含技能筛选)
 *   - POST /close               关闭会话 (客户/坐席主动)
 *   - POST /transfer            转接会话
 *   - POST /rate                客户 CSAT 评分
 *   - GET  /messages/unread     未读消息数 (拉离线)
 *
 * 鉴权: 所有端点需 JWT, UserContext 提取 uid+role
 */
@Tag(name = "会话")
@RestController
@RequestMapping("/api/im/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final PresenceService presenceService;
    private final OfflineMessageStore offlineStore;
    private final UnreadCounterService unreadCounterService;
    private final AgentStatusService agentStatusService;

    @Operation(summary = "客户创建会话 (可指定技能; mode=bot 选智能客服)")
    @PostMapping("/create")
    public ApiResponse<ChatSession> create(@RequestParam(required = false) String skill,
                                           @RequestParam(required = false, defaultValue = "human") String mode) {
        boolean isBot = "bot".equalsIgnoreCase(mode);
        return sessionService.create(skill, isBot);
    }

    /**
     * 坐席抢单 (核心端点).
     * - 不传 sessionId: 从 Redis 队列自动取下一个
     * - 传 sessionId: 手动接起指定会话 (v2.5.0, Agent.vue 接起按钮)
     * - CAS: MySQL 行锁防串线, 多坐席同时抢 -> 1 成功 / 其他 409
     */
    @Operation(summary = "坐席抢单")
    @PostMapping("/claim")
    public ApiResponse<ChatSession> claim(@RequestParam(required = false) Long sessionId) {
        return sessionService.claim(sessionId);
    }

    @Operation(summary = "会话转接")
    @PostMapping("/{sessionId}/transfer")
    public ApiResponse<ChatSession> transfer(@PathVariable Long sessionId,
                                              @RequestParam Long toAgentId,
                                              @RequestParam(required = false) String reason) {
        return sessionService.transfer(sessionId, toAgentId, reason);
    }

    @Operation(summary = "CSAT 评分")
    @PostMapping("/{sessionId}/rate")
    public ApiResponse<Void> rate(@PathVariable Long sessionId,
                                  @RequestParam Integer rating,
                                  @RequestParam(required = false) String comment) {
        return sessionService.rate(sessionId, rating, comment);
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/mine")
    public ApiResponse<List<com.chat.im.dto.SessionView>> mine() {
        return sessionService.mySessions();
    }

    @Operation(summary = "坐席查看等待队列")
    @GetMapping("/waiting")
    public ApiResponse<List<Long>> waiting() {
        return sessionService.waitingQueue();
    }

    @Operation(summary = "关闭会话")
    @PostMapping("/{sessionId}/close")
    public ApiResponse<Void> close(@PathVariable Long sessionId) {
        return sessionService.close(sessionId);
    }

    @Operation(summary = "客户主动退出会话 (幂等)")
    @PostMapping("/{sessionId}/exit")
    public ApiResponse<Void> exit(@PathVariable Long sessionId,
                                  @RequestParam(required = false) String reason) {
        return sessionService.customerExit(sessionId, reason);
    }

    @Operation(summary = "客户申请转接其他坐席")
    @PostMapping("/{sessionId}/request-transfer")
    public ApiResponse<ChatSession> requestTransfer(@PathVariable Long sessionId,
                                                    @RequestParam(required = false) String preferredSkill) {
        return sessionService.customerRequestTransfer(sessionId, preferredSkill);
    }

    @Operation(summary = "机器人会话中申请转人工 (旧会话关闭, 新建一个人工会话)")
    @PostMapping("/{sessionId}/transfer-to-human")
    public ApiResponse<ChatSession> transferToHuman(@PathVariable Long sessionId,
                                                    @RequestParam(required = false) String skill) {
        return sessionService.transferToHuman(sessionId, skill);
    }

    @Operation(summary = "启动状态 (在线 + 离线数 + 未读总数)")
    @GetMapping("/bootstrap")
    public ApiResponse<Map<String, Object>> bootstrap() {
        Map<String, Object> m = new HashMap<>();
        Long uid = UserContext.userId();
        m.put("userId", uid);
        m.put("online", presenceService.isOnline(uid));
        m.put("offlineSize", offlineStore.size(uid));
        return ApiResponse.ok(m);
    }

    @Operation(summary = "拉取并清空离线消息")
    @GetMapping("/offline/drain")
    public ApiResponse<List<MessageDTO>> drain() {
        return ApiResponse.ok(offlineStore.drain(UserContext.userId()));
    }

    @Operation(summary = "会话未读数")
    @GetMapping("/{sessionId}/unread")
    public ApiResponse<Long> unread(@PathVariable Long sessionId) {
        return ApiResponse.ok(unreadCounterService.get(UserContext.userId(), sessionId));
    }

    @Operation(summary = "坐席设置状态")
    @PostMapping("/agent/status")
    public ApiResponse<Void> setAgentStatus(@RequestParam String status) {
        agentStatusService.setStatus(UserContext.userId(), status);
        return ApiResponse.ok();
    }

    @Operation(summary = "获取我的坐席状态")
    @GetMapping("/agent/status")
    public ApiResponse<Map<String, String>> getAgentStatus() {
        return ApiResponse.ok(Map.of("status", agentStatusService.getStatus(UserContext.userId())));
    }
}