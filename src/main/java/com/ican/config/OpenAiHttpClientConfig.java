package com.ican.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * OpenAI HTTP客户端超时配置
 * 解决长文档处理或复杂RAG查询时的超时问题
 *
 * @author ICan
 * @since 2024-12-20
 */
@Slf4j
@Configuration
public class OpenAiHttpClientConfig {

    /**
     * 自定义 RestClient 超时配置
     * 适用于长文档摘要、教学计划生成等耗时操作
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        log.info("配置 OpenAI HTTP 客户端超时: 连接超时=30s, 读取超时=300s");
        
        return restClientBuilder -> restClientBuilder
                .requestFactory(clientHttpRequestFactory());
    }

    /**
     * 创建支持长超时的 HTTP 请求工厂
     */
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时：30秒
        factory.setConnectTimeout(Duration.ofSeconds(30));
        // 读取超时：5分钟（适合长文档处理、论文摘要等耗时操作）
        factory.setReadTimeout(Duration.ofSeconds(300));
        return factory;
    }
}
