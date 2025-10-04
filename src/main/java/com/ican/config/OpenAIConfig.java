
package com.ican.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * DeepSeek AI 配置类
 * 用于配置 HTTP 客户端的超时时间,解决调用 API 时的超时问题
 * 
 * 通过配置 RestClientCustomizer 来设置全局的超时参数:
 * - 连接超时: 30秒
 * - 读取超时: 180秒 (足够长的时间来等待 AI 生成响应)
 */
@Configuration
public class OpenAIConfig {

    /**
     * 配置 RestClient 自定义器,设置超时时间
     * 这个自定义器会被 Spring AI 的自动配置使用
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> restClientBuilder.requestFactory(clientHttpRequestFactory());
    }

    /**
     * 创建自定义的 HTTP 请求工厂,配置超时参数
     */
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时时间: 30秒
        factory.setConnectTimeout(Duration.ofSeconds(30));
        // 读取超时时间: 180秒 (AI 响应可能需要较长时间,特别是复杂对话)
        factory.setReadTimeout(Duration.ofSeconds(180));
        return factory;
    }
}
