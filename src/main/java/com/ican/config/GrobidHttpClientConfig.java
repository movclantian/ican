package com.ican.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * GROBID 专用 HTTP 客户端配置
 * 
 * 设置合理的连接/读取超时，避免外部服务故障情况下阻塞业务线程。
 */
@Configuration
@ConditionalOnClass(RestTemplate.class)
public class GrobidHttpClientConfig {

    @Bean("grobidRestTemplate")
    public RestTemplate grobidRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时（毫秒）
        factory.setConnectTimeout(3000);
        // 读取超时（毫秒）
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}
