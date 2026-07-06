package com.chat.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.auth.dto.RegisterDTO;
import com.chat.auth.entity.User;
import com.chat.auth.mapper.UserMapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.LoginDTO;
import com.chat.common.dto.LoginVO;
import com.chat.common.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${chat.jwt.secret:change-me-please-use-a-32-byte-secret}")
    private String jwtSecret;

    @Value("${chat.jwt.ttl-ms:86400000}")
    private long ttlMs;

    public ApiResponse<LoginVO> login(LoginDTO dto) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (u == null) {
            return ApiResponse.fail(401, "用户不存在");
        }
        if (u.getStatus() != null && u.getStatus() == 0) {
            return ApiResponse.fail(403, "账号已禁用");
        }
        if (!encoder.matches(dto.getPassword(), u.getPassword())) {
            return ApiResponse.fail(401, "密码错误");
        }
        String token = JwtUtil.issue(jwtSecret, u.getId(), u.getUsername(), u.getRole(), u.getNickname(), ttlMs);
        return ApiResponse.ok(new LoginVO(u.getId(), u.getUsername(), u.getNickname(), u.getRole(), token, ttlMs / 1000));
    }

    @Transactional
    public ApiResponse<LoginVO> register(RegisterDTO dto) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            return ApiResponse.fail(400, "用户名已存在");
        }
        User u = new User();
        u.setUsername(dto.getUsername());
        u.setPassword(encoder.encode(dto.getPassword()));
        u.setNickname(dto.getNickname());
        // 出于安全, 注册只允许 CUSTOMER, 坐席由管理员开通
        u.setRole(dto.getRole() == null ? CommonConstants.ROLE_CUSTOMER : dto.getRole());
        u.setStatus(1);
        userMapper.insert(u);

        String token = JwtUtil.issue(jwtSecret, u.getId(), u.getUsername(), u.getRole(), u.getNickname(), ttlMs);
        return ApiResponse.ok(new LoginVO(u.getId(), u.getUsername(), u.getNickname(), u.getRole(), token, ttlMs / 1000));
    }
}