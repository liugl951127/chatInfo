package com.chat.auth.controller;

import com.chat.auth.dto.RegisterDTO;
import com.chat.auth.service.AuthService;
import com.chat.common.api.ApiResponse;
import com.chat.common.dto.LoginDTO;
import com.chat.common.dto.LoginVO;
import com.chat.common.ratelimit.RateLimit;
import com.chat.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AuthController - 鉴权 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /login                登录 (返 JWT token + userId/role/skills)
 *   - POST /register             注册 (返 LoginVO 同登录)
 *   - GET  /me                   当前用户信息 (从 JWT 读 uid)
 *   - GET  /skills               坐席可服务技能列表 (仅客服可见)
 *   - GET  /users?role=...       用户列表 (按角色, 走 JWT)
 *
 * JWT:
 *   - HS256 + 共用 secret (cs-auth / cs-im / cs-gateway 三个服务 secret 必须一致)
 *   - 默认 8h 过期
 *   - JwtAuthInterceptor 解析后存 UserContext (ThreadLocal)
 */
@Tag(name = "鉴权")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    @RateLimit(key = "login", permits = 5, window = 60)
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return authService.login(dto);
    }

    @Operation(summary = "注册 (仅 CUSTOMER)")
    @PostMapping("/register")
    public ApiResponse<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return authService.register(dto);
    }

    @Operation(summary = "当前用户信息 (供前端启动时拉取)")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Map<String, Object> m = new HashMap<>();
        m.put("userId",  UserContext.userId());
        m.put("username", UserContext.username());
        m.put("role",    UserContext.role());
        m.put("nickname", UserContext.nickname());
        return ApiResponse.ok(m);
    }
}