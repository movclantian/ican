package com.ican.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Elasticsearch 原生客户端配置
 * 直接使用 elasticsearch-java 8.15.0 API
 * 
 * @author 席崇援
 * @since 2025-10-15
 */
@Slf4j
@Configuration
public class ElasticsearchClientConfig {
    
    private final ElasticsearchProperties properties;
    
    public ElasticsearchClientConfig(ElasticsearchProperties properties) {
        this.properties = properties;
    }
    
    /**
     * 配置 RestClient
     * 低层次 HTTP 客户端
     */
    @Bean
    public RestClient restClient() {
        List<String> uris = properties.getUris();
        HttpHost[] hosts = uris.stream()
            .map(HttpHost::create)
            .toArray(HttpHost[]::new);
        
        RestClient client = RestClient.builder(hosts)
            .setRequestConfigCallback(builder -> builder
                .setConnectTimeout(30000)  // 30秒连接超时
                .setSocketTimeout(60000))  // 60秒读取超时
            .build();
        
        log.info("Elasticsearch RestClient 已初始化: {}", uris);
        return client;
    }
    
    /**
     * Elasticsearch 配置属性类
     */
    @Data
    @Component
    @ConfigurationProperties(prefix = "elasticsearch")
    public static class ElasticsearchProperties {
        private List<String> uris;
    }
    
    /**
     * 配置 ElasticsearchClient
     * 高层次类型化客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        // 配置 Jackson 支持 Java 8 时间类型并使用 ISO-8601 格式
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // 禁用将日期写为时间戳,使用 ISO-8601 字符串格式
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // 创建传输层
        RestClientTransport transport = new RestClientTransport(
            restClient, 
            new JacksonJsonpMapper(objectMapper)
        );
        
        ElasticsearchClient client = new ElasticsearchClient(transport);
        log.info("ElasticsearchClient 已初始化");
        
        return client;
    }
}
