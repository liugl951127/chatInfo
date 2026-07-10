package com.chat.community;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsCommunityApplication - 群体智能社区 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: 客户发帖/UGC知识库/激励机制
 * 端口: 9008
 * 模块路径: cs-community
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.community", "com.chat.common"})
@MapperScan("com.chat.community.mapper")
public class CsCommunityApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsCommunityApplication.class, args);
    }
}
