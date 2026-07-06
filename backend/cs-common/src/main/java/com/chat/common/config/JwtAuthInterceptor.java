package com.chat.common.config;

import com.chat.common.constant.CommonConstants;
import com.chat.common.security.JwtUtil;
import com.chat.common.security.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * WebMvc 拦截器: 校验 JWT, 注入 UserContext。
 * <p>
 * 在 cs-auth / cs-im 服务中通过 WebMvcConfigurer 注册。
 * 网关层 (cs-gateway) 用 GlobalFilter 完成同样事情, 这里只服务于
 * 服务内部 Controller 直连时的鉴权 (例如 knife4j 调试)。
 */
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final String secret;

    public JwtAuthInterceptor(String secret) {
        this.secret = secret;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        // 放行 OPTIONS 与公开路径
        String path = req.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        // 公开接口: /auth/login, /auth/register
        if (path.equals("/auth/login") || path.equals("/auth/register")
                || path.startsWith("/auth/login/") || path.startsWith("/auth/register/")) {
            return true;
        }
        // Knife4j / 静态资源放行
        if (path.startsWith("/doc.html") || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui") || path.startsWith("/webjars")
                || path.startsWith("/favicon")) {
            return true;
        }

        String header = req.getHeader(CommonConstants.AUTH_HEADER);
        if (header == null || !header.startsWith(CommonConstants.AUTH_PREFIX)) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":401,\"message\":\"missing token\"}");
            return false;
        }
        String token = header.substring(CommonConstants.AUTH_PREFIX.length()).trim();
        try {
            Claims c = JwtUtil.parse(secret, token);
            UserContext.set(JwtUtil.uid(c), c.getSubject(), JwtUtil.role(c), JwtUtil.nick(c));
            return true;
        } catch (Exception e) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":401,\"message\":\"invalid or expired token\"}");
            return false;
        }
    }
}