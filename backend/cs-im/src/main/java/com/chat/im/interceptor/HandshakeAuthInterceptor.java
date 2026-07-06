package com.chat.im.interceptor;

import com.chat.common.constant.CommonConstants;
import com.chat.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器: 从 query string 或 header 中取 token, 校验后放入 attributes。
 * STOMP CONNECT 阶段 {@link StompAuthChannelInterceptor} 会读取这些 attributes 设置 Principal。
 * <p>
 * 生产建议 token 只走 header; 这里兼容 ?token=xxx 方便浏览器调试。
 */
@Slf4j
public class HandshakeAuthInterceptor implements HandshakeInterceptor {

    private final String secret;

    public HandshakeAuthInterceptor(String secret) {
        this.secret = secret;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse resp,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(req);
        if (token == null) {
            log.warn("[ws] handshake rejected: missing token");
            resp.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Claims c = JwtUtil.parse(secret, token);
            attributes.put("userId",   JwtUtil.uid(c));
            attributes.put("username", c.getSubject());
            attributes.put("role",     JwtUtil.role(c));
            attributes.put("nickname", JwtUtil.nick(c));
            return true;
        } catch (Exception e) {
            log.warn("[ws] handshake rejected: invalid token - {}", e.getMessage());
            resp.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse resp,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String extractToken(ServerHttpRequest req) {
        // 1) Authorization header
        String h = req.getHeaders().getFirst(CommonConstants.AUTH_HEADER);
        if (h != null && h.startsWith(CommonConstants.AUTH_PREFIX)) {
            return h.substring(CommonConstants.AUTH_PREFIX.length());
        }
        // 2) query string ?token=...
        if (req instanceof ServletServerHttpRequest sreq) {
            HttpServletRequest http = sreq.getServletRequest();
            String t = http.getParameter("token");
            if (t != null && !t.isEmpty()) return t;
        }
        return null;
    }
}