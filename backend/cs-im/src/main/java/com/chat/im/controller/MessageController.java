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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 消息相关:
 * <ul>
 *   <li>STOMP: 客户端发送 /app/send/{sessionId}, 服务端处理后回写 /user/queue/messages</li>
 *   <li>REST:  GET /api/im/session/{sessionId}/messages?limit=50 拉历史</li>
 * </ul>
 */
@Tag(name = "消息")
@RestController
@RequestMapping("/api/im/session")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * STOMP 入口: 客户端发送消息。
     * <p>
     * 通过 SimpMessageHeaderAccessor 拿到由 StompAuthChannelInterceptor 设置的 Principal。
     */
    @MessageMapping("/send/{sessionId}")
    public void send(@DestinationVariable Long sessionId,
                     MessageDTO msg,
                     SimpMessageHeaderAccessor accessor) {
        Principal p = accessor.getUser();
        if (!(p instanceof StompPrincipal sp)) {
            throw new IllegalArgumentException("unauthorized");
        }
        messageService.handleIncoming(sessionId, msg, sp.userId(), sp.role());
    }

    @Operation(summary = "拉历史消息")
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<MessageDTO>> history(@PathVariable Long sessionId,
                                                 @RequestParam(defaultValue = "50") Integer limit) {
        return messageService.history(sessionId, limit);
    }
}