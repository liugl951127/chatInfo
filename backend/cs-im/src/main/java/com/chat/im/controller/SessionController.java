package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatSession;
import com.chat.im.service.OfflineMessageStore;
import com.chat.im.service.SessionService;
import com.chat.im.service.PresenceService;
import com.chat.common.dto.MessageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "会话")
@RestController
@RequestMapping("/api/im/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final PresenceService presenceService;
    private final OfflineMessageStore offlineStore;

    @Operation(summary = "客户创建会话")
    @PostMapping("/create")
    public ApiResponse<ChatSession> create() {
        return sessionService.create();
    }

    @Operation(summary = "坐席领取一个等待会话")
    @PostMapping("/claim")
    public ApiResponse<ChatSession> claim() {
        return sessionService.claim();
    }

    @Operation(summary = "查看我的会话列表")
    @GetMapping("/mine")
    public ApiResponse<List<ChatSession>> mine() {
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

    @Operation(summary = "启动时拉取状态 (在线 + 离线消息)")
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
}