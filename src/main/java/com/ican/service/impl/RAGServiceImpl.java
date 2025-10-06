package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentMapper;
import com.ican.model.entity.DocumentDO;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 服务实现类
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGServiceImpl implements RAGService {
    
    private final OpenAiChatModel openAiChatModel;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    private final DocumentMapper documentMapper;
    
    @Value("classpath:/prompts/qa-with-context.st")
    private Resource qaPromptResource;
    
    @Value("classpath:/prompts/paper-summary.st")
    private Resource paperSummaryPromptResource;
    
    @Value("classpath:/prompts/teaching-plan.st")
    private Resource teachingPlanPromptResource;
    
    @Override
    public String ragChat(String conversationId, String query) {
        log.info("RAG问答: conversationId={}, query={}", conversationId, query);
        
        // 获取当前用户ID（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 获取对话历史
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history == null) {
            history = new ArrayList<>();
        }
        
        // 构建搜索请求 - 只检索当前用户有权访问的文档
        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .topK(ragConfig.getRetrieval().getTopK())
            .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build())
            .build();
        
        // 加载自定义 Prompt 模板
        PromptTemplate promptTemplate = new PromptTemplate(qaPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .promptTemplate(promptTemplate)
            .build();
        
        // 构建ChatClient
        ChatClient chatClient = ChatClient.builder(openAiChatModel).build();
        
        String response = chatClient.prompt()
            .advisors(qaAdvisor)
            .user(query)
            .call()
            .content();
        
        // 保存对话历史
        try {
            UserMessage userMessage = new UserMessage(query);
            chatMemoryRepository.saveAll(conversationId, List.of(userMessage));
        } catch (Exception e) {
            log.warn("保存RAG对话历史失败: conversationId={}", conversationId, e);
        }
        
        log.info("RAG问答完成: conversationId={}, responseLength={}", conversationId, response.length());
        return response;
    }
    
    @Override
    public String documentChat(Long documentId, String query) {
        log.info("文档问答: documentId={}, query={}", documentId, query);
        
        // 验证文档权限（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
        validateDocumentAccess(documentId, userId);
        
        // 构建搜索请求 - 只检索指定文档的内容
        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .topK(ragConfig.getRetrieval().getTopK())
            .filterExpression(new FilterExpressionBuilder().eq("documentId", documentId).build())
            .build();
        
        // 加载自定义 Prompt 模板
        PromptTemplate promptTemplate = new PromptTemplate(qaPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .promptTemplate(promptTemplate)
            .build();
        
        String response = ChatClient.builder(openAiChatModel).build()
            .prompt()
            .advisors(qaAdvisor)
            .user(query)
            .call()
            .content();
        
        return response;
    }
    
    @Override
    public PaperSummaryVO summarizePaper(Long documentId) {
        log.info("论文总结: documentId={}", documentId);
        
        // 验证文档权限（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
        validateDocumentAccess(documentId, userId);
        
        // 构建搜索请求 - 检索文档所有内容
        SearchRequest searchRequest = SearchRequest.builder()
            .query("请总结这篇论文的核心内容")
            .similarityThreshold(0.0) // 获取所有块
            .topK(50) // 获取更多块
            .filterExpression(new FilterExpressionBuilder().eq("documentId", documentId).build())
            .build();
        
        // 加载自定义 Prompt 模板
        PromptTemplate promptTemplate = new PromptTemplate(paperSummaryPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .promptTemplate(promptTemplate)
            .build();
        
        try {
            PaperSummaryVO summary = ChatClient.builder(openAiChatModel).build()
                .prompt()
                .advisors(qaAdvisor)
                .user("请按照要求总结这篇论文")
                .call()
                .entity(PaperSummaryVO.class);
            
            log.info("论文总结完成: documentId={}", documentId);
            return summary;
        } catch (Exception e) {
            log.error("论文总结失败: documentId={}", documentId, e);
            throw new BusinessException("论文总结失败,请稍后重试");
        }
    }
    
    @Override
    public TeachingPlanVO generateTeachingPlan(String topic, String grade, String subject, List<Long> documentIds) {
        log.info("生成教学设计: topic={}, grade={}, subject={}", topic, grade, subject);
        
        // 获取当前用户ID
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 构建搜索请求
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
            .query(topic)
            .similarityThreshold(0.6)
            .topK(10)
            .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build());
        
        SearchRequest searchRequest = searchBuilder.build();
        
        // 加载自定义 Prompt 模板
        PromptTemplate promptTemplate = new PromptTemplate(teachingPlanPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .promptTemplate(promptTemplate)
            .build();
        
        try {
            TeachingPlanVO teachingPlan = ChatClient.builder(openAiChatModel).build()
                .prompt()
                .advisors(qaAdvisor)
                .user(u -> u.text("请根据参考资料生成教学设计方案")
                    .param("topic", topic)
                    .param("grade", grade)
                    .param("subject", subject))
                .call()
                .entity(TeachingPlanVO.class);
            
            log.info("教学设计生成完成: topic={}", topic);
            return teachingPlan;
        } catch (Exception e) {
            log.error("教学设计生成失败: topic={}", topic, e);
            throw new BusinessException("教学设计生成失败,请稍后重试");
        }
    }
    
    /**
     * 验证文档访问权限
     */
    private void validateDocumentAccess(Long documentId, Long userId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该文档");
        }
    }
}

