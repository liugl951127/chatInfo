package com.chat.success.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * ImClientConfig - cs-im 客户端配置.
 * ----------------------------------------------------------------------------
 * 用于 AgentStatsService 通过 RestTemplate 调 cs-im 的
 *   GET /api/im/stats/agent/{agentId}
 * <p>
 * 配置项 (application.yml):
 *   chat.im.base-url: http://localhost:9002  (cs-im 服务地址)
 * <p>
 * 特性:
 *   - 3s 连接超时 + 5s 读取超时 (统计接口, 宁可快速失败也不能拖死 dashboard)
 *   - 单例 Bean, 线程安全
 */
@Slf4j
@Configuration
public class ImClientConfig {

    @Value("${chat.im.base-url:http://localhost:9002}")
    private String imBaseUrl;

    @Bean
    public RestTemplate imRestTemplate(RestTemplateBuilder builder) {
        log.info("[im-client] init RestTemplate, base={}", imBaseUrl);
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String getImBaseUrl() {
        return imBaseUrl;
    }
}
