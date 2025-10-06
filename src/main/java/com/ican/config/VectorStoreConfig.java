package com.ican.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/**
 * 向量存储配置类
 *
 * @author ICan
 * @since 2024-10-07
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.ai.vectorstore.redis.index-name:vector-index}")
    private String indexName;

    @Value("${spring.ai.vectorstore.redis.prefix:vector:}")
    private String prefix;

    /**
     * 配置 JedisPooled
     *
     * @return JedisPooled实例
     */
    @Bean
    public JedisPooled jedisPooled() {
        log.info("初始化 Redis 连接: {}:{}", redisHost, redisPort);
        return new JedisPooled(redisHost, redisPort);
    }

    /**
     * 配置 Redis 向量存储
     *
     * @param embeddingModel 嵌入模型
     * @param jedisPooled Jedis连接池
     * @return Redis向量存储实例
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, JedisPooled jedisPooled) {
        log.info("初始化 Redis 向量存储...");
        log.info("嵌入模型: {}", embeddingModel.getClass().getSimpleName());
        log.info("索引名称: {}, 前缀: {}", indexName, prefix);

        return RedisVectorStore.builder(jedisPooled, embeddingModel)
            .indexName(indexName)
            .prefix(prefix)
            .initializeSchema(false)  // 禁用自动初始化，需要Redis Stack支持
            .build();
    }
}