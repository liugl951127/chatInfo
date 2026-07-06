package com.chat.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 */
@Data
public class LoginDTO {
    @NotBlank private String username;
    @NotBlank private String password;
}