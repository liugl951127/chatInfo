package com.chat.voice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsVoiceApplication - 智能语音电话 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: ASR/TTS/AI Agent/通话录音
 * 端口: 9010
 * 模块路径: cs-voice
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.voice", "com.chat.common"})
@MapperScan("com.chat.voice.mapper")
public class CsVoiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsVoiceApplication.class, args);
    }
}
