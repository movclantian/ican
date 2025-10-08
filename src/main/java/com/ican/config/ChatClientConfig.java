package com.ican.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatClient 配置类
 * 创建全局的 ChatClient Bean，避免每次对话都创建新实例
 * 
 * @author ICan
 * @since 2024-10-08
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {
    
    private final OpenAiChatModel openAiChatModel;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    
    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;
    
    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private Integer maxTokens;

    @Value("${chat.max-history-messages}")
    private Integer maxHistoryMessages;
    
    /**
     * 普通聊天的 ChatClient（不带 RAG）
     * 使用 MessageChatMemoryAdvisor 自动管理对话历史
     */
    @Bean("normalChatClient")
    @Primary
    public ChatClient normalChatClient() {
        log.info("初始化普通聊天 ChatClient");
        
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors()
            .defaultOptions(OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build())
            .defaultSystem("你是一个智能AI助手，请友好、专业地回答用户的问题。")
            .build();
    }
    
    /**
     * RAG 聊天的 ChatClient（带向量检索增强）
     * 使用 QuestionAnswerAdvisor 自动处理文档检索和上下文增强
     */
    @Bean("ragChatClient")
    public ChatClient ragChatClient() {
        log.info("初始化 RAG ChatClient，topK={}, similarityThreshold={}", 
            ragConfig.getRetrieval().getTopK(), 
            ragConfig.getRetrieval().getSimilarityThreshold());
        
        // 配置默认的检索参数
        SearchRequest defaultSearchRequest = SearchRequest.builder()
            .topK(ragConfig.getRetrieval().getTopK())
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .build();
        
        // 创建 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(defaultSearchRequest)
            .build();
        
        return ChatClient.builder(openAiChatModel)
            .defaultAdvisors(qaAdvisor)
            .defaultOptions(OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build())
            .defaultSystem("""
                你是一个专业的AI助手，擅长基于提供的参考资料回答问题。
                
                回答原则：
                1. 准确性：回答必须基于参考资料，不要编造信息
                2. 完整性：回答要全面，覆盖问题的各个方面
                3. 清晰性：用简洁明了的语言表达
                4. 引用性：如果可能，标注信息来源
                5. 诚实性：如果参考资料中没有相关信息，请如实说明
                """)
            .build();
    }
    
    /**
     * ChatMemory Bean - 用于管理对话历史
     * 使用 MessageWindowChatMemory 自动限制历史消息数量
     */
    @Bean
    public ChatMemory chatMemory() {
        log.info("初始化 ChatMemory，maxMessages={}", maxHistoryMessages);
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(maxHistoryMessages)
            .build();
    }
}
