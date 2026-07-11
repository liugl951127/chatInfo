package com.chat.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 排除 Spring Security 默认配置 (我们用自定义 JWT, 不需要 form-login)。
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
@MapperScan({"com.chat.auth.mapper", "com.chat.common.mapper"})
@ComponentScan({"com.chat.auth", "com.chat.common"})
public class CsAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsAuthApplication.class, args);
    }
}