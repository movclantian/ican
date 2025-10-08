package com.ican.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置类
 * 
 * 注意：由于Spring AI Pinecone自动配置已经提供了VectorStore Bean，
 * 我们不再需要手动配置。Pinecone配置通过application.yaml中的
 * spring.ai.vectorstore.pinecone.* 属性进行配置。
 *
 * @author ICan
 * @since 2024-10-07
 */
@Slf4j
@Configuration
public class VectorStoreConfig {
    
    // Spring AI Pinecone 自动配置已提供 VectorStore Bean
    // 配置通过 application.yaml 中的 spring.ai.vectorstore.pinecone.* 属性进行
    
    // Redis 向量存储配置 - 已切换到 Pinecone
    /*
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.ai.vectorstore.redis.index-name:vector-index}")
    private String indexName;

    @Value("${spring.ai.vectorstore.redis.prefix:vector:}")
    private String prefix;

    @Bean
    public JedisPooled jedisPooled() {
        log.info("初始化 Redis 连接: {}:{}", redisHost, redisPort);
        return new JedisPooled(redisHost, redisPort);
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, JedisPooled jedisPooled) {
        log.info("初始化 Redis 向量存储...");
        log.info("嵌入模型: {}", embeddingModel.getClass().getSimpleName());
        
        try {
            // 根据Spring AI 1.0.3版本，使用简化的配置方式
            PineconeVectorStore vectorStore = PineconeVectorStore.builder(embeddingModel)
                .apiKey(apiKey)
                .indexName(indexName)
                .namespace(namespace)
                .build();
            
            log.info("Pinecone 向量存储初始化成功");
            return vectorStore;
        } catch (Exception e) {
            log.error("Pinecone 向量存储初始化失败", e);
            throw new RuntimeException("向量存储初始化失败", e);
        }
    }*/
}

