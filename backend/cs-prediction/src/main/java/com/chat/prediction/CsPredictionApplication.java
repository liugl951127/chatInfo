package com.chat.prediction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CsPredictionApplication - 预见式服务 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: 订单异常检测/流失预警/价值客户关怀/主动推送
 * 端口: 9005
 * 模块路径: cs-prediction
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.prediction", "com.chat.common"})
@MapperScan("com.chat.prediction.mapper")
public class CsPredictionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsPredictionApplication.class, args);
    }
}
