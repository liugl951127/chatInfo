package com.chat.video;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsVideoApplication - 视频会话 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: WebRTC P2P 1v1/屏幕共享/合规录制
 * 端口: 9009
 * 模块路径: cs-video
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.video", "com.chat.common"})
@MapperScan("com.chat.video.mapper")
public class CsVideoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsVideoApplication.class, args);
    }
}
