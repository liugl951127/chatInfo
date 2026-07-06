package com.chat.im.interceptor;

import com.chat.im.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP 入站拦截器:
 * <ul>
 *   <li>CONNECT: 从 handshake attributes 拿用户信息, 设置 Principal + 上线</li>
 *   <li>DISCONNECT: 标记下线</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final PresenceService presenceService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand cmd = accessor.getCommand();
        if (cmd == null) return message;

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (cmd == StompCommand.CONNECT) {
            if (attrs == null) {
                throw new IllegalArgumentException("no handshake attributes");
            }
            Long uid = (Long) attrs.get("userId");
            String username = (String) attrs.get("username");
            String role = (String) attrs.get("role");
            String nickname = (String) attrs.get("nickname");
            if (uid == null) {
                throw new IllegalArgumentException("unauthorized");
            }
            Principal principal = new StompPrincipal(uid, username, role, nickname);
            accessor.setUser(principal);
            presenceService.online(uid, role);
            log.info("[ws] CONNECT user={} ({}) role={}", username, uid, role);
        } else if (cmd == StompCommand.DISCONNECT) {
            Principal p = accessor.getUser();
            if (p instanceof StompPrincipal sp) {
                presenceService.offline(sp.userId(), sp.role());
                log.info("[ws] DISCONNECT user={} ({})", sp.username(), sp.userId());
            }
        }
        return message;
    }

    /** Principal 实现, 持有我们的用户信息。 */
    public record StompPrincipal(Long userId, String username, String role, String nickname) implements Principal {
        @Override public String getName() { return String.valueOf(userId); }
    }
}