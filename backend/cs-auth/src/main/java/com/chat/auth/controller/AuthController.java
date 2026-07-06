package com.chat.auth.controller;

import com.chat.auth.dto.RegisterDTO;
import com.chat.auth.service.AuthService;
import com.chat.common.api.ApiResponse;
import com.chat.common.dto.LoginDTO;
import com.chat.common.dto.LoginVO;
import com.chat.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "鉴权")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
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