package com.chat.ai.config;

import com.chat.common.ai.FaqEngine;
import com.chat.common.ai.IntentClassifier;
import com.chat.common.ai.SentimentAnalyzer;
import com.chat.common.ai.TfIdfEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AiConfig - 显式注册 cs-common/ai 下的本地推理 Bean.
 *
 * 说明: cs-common 下的类大多带 @Service/@Component, 但部分类 (如 FaqEngine)
 * 是普通 class, 需要主动注册. 这里统一定义以便在 standalone 模式下
 * 不依赖 @ComponentScan 推断, 启动更加可控.
 */
@Configuration
public class AiConfig {

    @Bean
    public IntentClassifier intentClassifier() {
        return new IntentClassifier();
    }

    @Bean
    public SentimentAnalyzer sentimentAnalyzer() {
        return new SentimentAnalyzer();
    }

    @Bean
    public TfIdfEmbedder tfIdfEmbedder() {
        return new TfIdfEmbedder(256);
    }

    @Bean
    public FaqEngine faqEngine(TfIdfEmbedder embedder) {
        return new FaqEngine(embedder);
    }
}
