package com.chat.cdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsCdpApplication - 数字孪生 360 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: 客户画像/标签/行为事件流
 * 端口: 9006
 * 模块路径: cs-cdp
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.cdp", "com.chat.common"})
@MapperScan("com.chat.cdp.mapper")
public class CsCdpApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsCdpApplication.class, args);
    }
}
