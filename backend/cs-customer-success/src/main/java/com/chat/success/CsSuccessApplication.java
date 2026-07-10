package com.chat.success;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsSuccessApplication - 客户成功 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: 健康分/onboarding/续约预测
 * 端口: 9007
 * 模块路径: cs-customer-success
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.success", "com.chat.common"})
@MapperScan("com.chat.success.mapper")
public class CsSuccessApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsSuccessApplication.class, args);
    }
}
