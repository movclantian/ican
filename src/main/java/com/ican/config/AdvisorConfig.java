package com.ican.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 顾问配置类
 * 
 * <p>提供三种核心的聊天记忆顾问：</p>
 * <ul>
 *   <li><b>MessageChatMemoryAdvisor</b> - 结构化对话历史管理（推荐，默认）</li>
 *   <li><b>PromptChatMemoryAdvisor</b> - 文本拼接式对话历史</li>
 *   <li><b>VectorStoreChatMemoryAdvisor</b> - 向量检索式对话记忆（创新）</li>
 * </ul>
 * 
 * <p>顾问执行顺序（Order）说明：</p>
 * <ul>
 *   <li>数字越小，优先级越高</li>
 *   <li>记忆类顾问通常在 RAG 顾问之前执行</li>
 *   <li>建议顺序：记忆顾问(0) → RAG顾问(1) → 安全顾问(2) → 日志顾问(999)</li>
 * </ul>
 * 
 * @author 席崇援
 * @since 2025-10-15
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdvisorConfig {
    
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    
    @Value("${advisor.memory.default-order:0}")
    private Integer memoryAdvisorOrder;
    
    @Value("${advisor.memory.vector-top-k:5}")
    private Integer vectorMemoryTopK;
    
    @Value("${advisor.memory.vector-similarity-threshold:0.7}")
    private Double vectorMemorySimilarityThreshold;

   
    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor() {      
        return MessageChatMemoryAdvisor.builder(chatMemory)
            .order(memoryAdvisorOrder)
            .build();
    }

   
    @Bean
    public PromptChatMemoryAdvisor promptChatMemoryAdvisor() {
        return PromptChatMemoryAdvisor.builder(chatMemory)
            .order(memoryAdvisorOrder)
            .build();
    }

    @Bean
    public VectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor() {
        return VectorStoreChatMemoryAdvisor.builder(vectorStore)
            .order(memoryAdvisorOrder)
            .build();
    }
}
