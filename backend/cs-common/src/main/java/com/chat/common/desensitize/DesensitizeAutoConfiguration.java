package com.chat.common.desensitize;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DesensitizeAutoConfiguration - 脱敏自动配置.
 * ----------------------------------------------------------------------------
 * 注册 Jackson Module, 让带 @Desensitize 字段自动脱敏.
 * 引入 cs-common 的服务自动生效, 无需额外配置.
 */
@Configuration
@ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
public class DesensitizeAutoConfiguration {

    @Bean
    public SimpleModule desensitizeModule() {
        SimpleModule module = new SimpleModule("DesensitizeModule");
        module.addSerializer(String.class, new DesensitizeSerializer(Desensitize.Type.DEFAULT));
        return module;
    }
}