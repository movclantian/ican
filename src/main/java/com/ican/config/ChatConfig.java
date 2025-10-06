package com.ican.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 聊天配置类
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chat")
public class ChatConfig {
    
    /**
     * 历史消息最大数量(防止 Token 超限)
     * 默认保留最近20条消息(10轮对话)
     */
    private Integer maxHistoryMessages = 20;
    
    /**
     * AI 响应温度参数(0.0-2.0)
     * 0.0 更确定性,2.0 更随机
     */
    private Double temperature = 0.7;
    
    /**
     * AI 响应最大 Token 数
     */
    private Integer maxTokens = 2000;
    
    /**
     * 是否异步生成会话标题
     */
    private Boolean asyncTitleGeneration = true;
    
    /**
     * 是否记录 Token 使用情况
     */
    private Boolean logTokenUsage = true;
}

