package com.chat.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * CsAiApplication - Java 自研 AI 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责:
 *   - 复用 cs-common/ai 下的 LocalAiService / FaqEngine / IntentClassifier /
 *     SentimentAnalyzer / TfIdfEmbedder 等本地推理组件
 *   - 对外暴露 RESTful AI 能力 (FAQ 检索/意图识别/情感分析/文本相似度)
 *   - 独立运行时使用 H2 内存库 + Mock 数据, 不依赖 mariadb/redis
 * 端口: 8087
 * 模块路径: cs-ai
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.chat.ai", "com.chat.common"})
@MapperScan("com.chat.ai.mapper")
public class CsAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsAiApplication.class, args);
    }
}
