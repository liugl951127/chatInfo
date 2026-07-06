package com.chat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {
    @NotBlank @Size(min = 3, max = 32)
    private String username;
    @NotBlank @Size(min = 6, max = 32)
    private String password;
    @NotBlank @Size(max = 32)
    private String nickname;
    /** CUSTOMER / AGENT (注册默认 CUSTOMER, AGENT 由 admin 创建) */
    private String role;
}