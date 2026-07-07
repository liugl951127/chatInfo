package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.dto.MessageDTO;
import com.chat.im.interceptor.StompAuthChannelInterceptor.StompPrincipal;
import com.chat.im.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Tag(name = "消息")
@RestController
@RequestMapping("/api/im/session")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @MessageMapping("/send/{sessionId}")
    public void send(@DestinationVariable Long sessionId, MessageDTO msg,
                     SimpMessageHeaderAccessor accessor) {
        Principal p = accessor.getUser();
        if (!(p instanceof StompPrincipal sp)) {
            throw new IllegalArgumentException("unauthorized");
        }
        messageService.handleIncoming(sessionId, msg, sp.userId(), sp.role());
    }

    /** STOMP 端: 客户端发 typing 事件 */
    @MessageMapping("/typing/{sessionId}")
    public void typing(@DestinationVariable Long sessionId, SimpMessageHeaderAccessor accessor,
                       java.util.Map<String, Object> payload) {
        Principal p = accessor.getUser();
        if (!(p instanceof StompPrincipal sp)) return;
        messageService.typing(sessionId, Boolean.TRUE.equals(payload.get("typing")));
    }

    @Operation(summary = "拉历史消息")
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<MessageDTO>> history(@PathVariable Long sessionId,
                                                 @RequestParam(defaultValue = "50") Integer limit) {
        return messageService.history(sessionId, limit);
    }

    @Operation(summary = "撤回消息 (2 分钟内)")
    @PostMapping("/message/{messageId}/recall")
    public ApiResponse<Void> recall(@PathVariable Long messageId) {
        return messageService.recall(messageId);
    }

    @Operation(summary = "标记单条消息已读")
    @PostMapping("/message/{messageId}/read")
    public ApiResponse<Void> markRead(@PathVariable Long messageId) {
        return messageService.markRead(messageId);
    }

    @Operation(summary = "标记整个会话已读")
    @PostMapping("/{sessionId}/read-all")
    public ApiResponse<Void> markSessionRead(@PathVariable Long sessionId) {
        return messageService.markSessionRead(sessionId);
    }
}