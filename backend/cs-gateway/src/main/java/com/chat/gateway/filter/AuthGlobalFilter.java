package com.chat.gateway.filter;

import com.chat.common.constant.CommonConstants;
import com.chat.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局鉴权过滤器: 校验 JWT, 把 user 信息透传给下游服务 (header 形式)。
 * 排除路径: /auth/login, /auth/register, /ws/**
 * <p>
 * 下游服务通过 cs-common 的 JwtAuthInterceptor 再次校验, 保证即使绕过网关也安全。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITELIST = List.of(
            "/auth/login",
            "/auth/register"
    );

    @Value("${chat.jwt.secret:change-me-please-use-a-32-byte-secret}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        log.debug("[gateway] {} {}", exchange.getRequest().getMethod() == null ? "" : exchange.getRequest().getMethod().name(), path);

        // 1) 放行白名单
        for (String w : WHITELIST) {
            if (path.equals(w) || path.startsWith(w + "/")) {
                return chain.filter(exchange);
            }
        }
        // 2) WebSocket 握手由 STOMP CONNECT 帧自己带 JWT, 这里跳过
        if (path.startsWith("/ws") || path.startsWith("/im/ws")) {
            return chain.filter(exchange);
        }
        // 3) OPTIONS
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod() == null ? "" : exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }

        // 4) 校验 JWT
        String header = exchange.getRequest().getHeaders().getFirst(CommonConstants.AUTH_HEADER);
        if (header == null || !header.startsWith(CommonConstants.AUTH_PREFIX)) {
            return reject(exchange, "missing token");
        }
        String token = header.substring(CommonConstants.AUTH_PREFIX.length()).trim();
        try {
            Claims c = JwtUtil.parse(secret, token);
            // 5) 透传给下游
            var mutated = exchange.getRequest().mutate()
                    .header("X-User-Id",    String.valueOf(JwtUtil.uid(c)))
                    .header("X-Username",   c.getSubject())
                    .header("X-User-Role",  String.valueOf(JwtUtil.role(c)))
                    .header("X-Nickname",   String.valueOf(JwtUtil.nick(c)))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.warn("[gateway] token invalid: {}", e.getMessage());
            return reject(exchange, "invalid or expired token");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return resp.writeWith(Mono.just(resp.bufferFactory()
                .wrap(("{\"code\":401,\"message\":\"" + reason + "\"}")
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}