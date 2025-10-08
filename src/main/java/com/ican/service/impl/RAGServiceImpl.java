package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentMapper;
import com.ican.mapper.TeachingPlanMapper;
import com.ican.model.entity.DocumentDO;
import com.ican.model.entity.TeachingPlanDO;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.TeachingPlanListVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.util.List;
import java.util.stream.Collectors;

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
    
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    private final DocumentMapper documentMapper;
    private final TeachingPlanMapper teachingPlanMapper;

    
    // 注入 ChatClient Bean
    private final ChatClient ragChatClient;
    
    // 注入提示词模板
    private final String ragQAPromptTemplate;
    private final String documentQAPromptTemplate;
    private final String paperSummaryPromptTemplate;
    private final String teachingPlanPromptTemplate;
    
    // JSON 转换器
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String ragChat(String conversationId, String query) {
        log.info("RAG问答: conversationId={}, query={}", conversationId, query);
        
        // 获取当前用户ID（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
                
        // 构建搜索请求 - 只检索当前用户有权访问的文档
        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .topK(ragConfig.getRetrieval().getTopK())
            .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build())
            .build();
        
        // 创建针对当前用户的 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .build();
        
        // 使用提示词模板构建用户提示
        String userPrompt = ragQAPromptTemplate.replace("{question}", query);
        
        // 使用注入的 ragChatClient 执行 RAG 问答
        String response = ragChatClient.prompt()
            .advisors(qaAdvisor)  // 动态添加用户特定的 Advisor
            .user(userPrompt)
            .call()
            .content();

        // 保存对话历史
        try {
            UserMessage userMessage = new UserMessage(query);
            chatMemoryRepository.saveAll(conversationId, List.of(userMessage));
        } catch (Exception e) {
            log.warn("保存RAG对话历史失败: conversationId={}", conversationId, e);
        }
        
        // 记录查询日志
        
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
        
        // 创建针对指定文档的 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .build();
    
        
        // 使用提示词模板构建用户提示
        String userPrompt = documentQAPromptTemplate.replace("{question}", query);
        
        // 使用注入的 ragChatClient 执行文档问答
        String response = ragChatClient.prompt()
            .advisors(qaAdvisor)  // 动态添加文档特定的 Advisor
            .user(userPrompt)
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
        
        // 不使用Prompt模板，直接构建提示词避免StringTemplate解析问题
        // PromptTemplate promptTemplate = new PromptTemplate(paperSummaryPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG - 不设置promptTemplate避免StringTemplate问题
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            // .promptTemplate(promptTemplate)  // 注释掉避免StringTemplate问题
            .build();
        
        try {
            // 使用注入的论文总结提示词模板
            String userPrompt = paperSummaryPromptTemplate;
            
            // 执行RAG并获取结构化输出（使用注入的 ragChatClient）
            PaperSummaryVO summary = ragChatClient.prompt()
                .advisors(qaAdvisor)  // 动态添加 Advisor
                .system("你是一位资深的学术研究专家，擅长阅读和分析学术论文。请严格按照指定的JSON格式进行总结，内容要准确、客观、专业。")
                .user(userPrompt)
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
        
        // 构建搜索请求 - 检索与教学主题相关的文档
        SearchRequest searchRequest = SearchRequest.builder()
            .query(topic)
            .similarityThreshold(0.6)
            .topK(10)
            .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build())
            .build();
        
        // 不使用Prompt模板，直接构建提示词避免StringTemplate解析问题
        // PromptTemplate promptTemplate = new PromptTemplate(teachingPlanPromptResource);
        
        // 使用 QuestionAnswerAdvisor 进行 RAG - 不设置promptTemplate避免StringTemplate问题
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            // .promptTemplate(promptTemplate)  // 注释掉避免StringTemplate问题
            .build();
        
        try {
            // 使用注入的教学设计提示词模板，替换占位符
            String userPrompt = teachingPlanPromptTemplate
                .replace("{topic}", topic)
                .replace("{grade}", grade)
                .replace("{subject}", subject);
            
            log.info("发送给AI的提示词: {}", userPrompt);
            
            // 执行RAG并获取结构化输出（使用注入的 ragChatClient）
            TeachingPlanVO teachingPlan = ragChatClient.prompt()
                .advisors(qaAdvisor)  // 动态添加 Advisor
                .system("你是一位经验丰富的教学设计专家。请根据提供的参考资料和要求，生成完整详细的教学设计方案。必须严格按照指定的JSON格式返回，所有字段都必须填写完整，不要使用占位符或空数组。")
                .user(userPrompt)
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
    
    /**
     * 保存教学设计
     */
    @Override
    public Long saveTeachingPlan(TeachingPlanVO teachingPlanVO) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();
        
        try {
            TeachingPlanDO teachingPlan = new TeachingPlanDO();
            teachingPlan.setUserId(userId);
            teachingPlan.setTitle(teachingPlanVO.getTitle());
            teachingPlan.setGrade(teachingPlanVO.getGrade());
            teachingPlan.setSubject(teachingPlanVO.getSubject());
            
            // 将TeachingPlanVO转换为JSON保存
            String contentJson = objectMapper.writeValueAsString(teachingPlanVO);
            teachingPlan.setContent(contentJson);
            
            // 保存关联的文档ID列表 (暂时不处理,TeachingPlanVO中没有该字段)
            // TeachingPlanDO会自动处理JSON字段
            
            teachingPlanMapper.insert(teachingPlan);
            log.info("教学设计保存成功: id={}, userId={}, title={}", 
                teachingPlan.getId(), userId, teachingPlanVO.getTitle());
            return teachingPlan.getId();
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON序列化失败: userId={}", userId, e);
            throw new BusinessException("教学设计保存失败");
        }
    }
    
    /**
     * 获取用户的教学设计列表
     */
    @Override
    public List<TeachingPlanListVO> getUserTeachingPlans(Long userId) {
        LambdaQueryWrapper<TeachingPlanDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TeachingPlanDO::getUserId, userId)
                    .eq(TeachingPlanDO::getIsDeleted, 0)
                    .orderByDesc(TeachingPlanDO::getUpdateTime);
        
        List<TeachingPlanDO> teachingPlans = teachingPlanMapper.selectList(queryWrapper);
        
        return teachingPlans.stream().map(plan -> {
            TeachingPlanListVO vo = new TeachingPlanListVO();
            vo.setId(plan.getId());
            vo.setTitle(plan.getTitle());
            vo.setGrade(plan.getGrade());
            vo.setSubject(plan.getSubject());
            vo.setCreateTime(plan.getCreateTime());
            vo.setUpdateTime(plan.getUpdateTime());
            return vo;
        }).collect(Collectors.toList());
    }
    
    /**
     * 获取教学设计详情
     */
    @Override
    public TeachingPlanVO getTeachingPlan(Long planId) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();
        
        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }
        
        // 验证权限
        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该教学设计");
        }
        
        try {
            // 从JSON反序列化教学设计内容
            TeachingPlanVO vo = objectMapper.readValue(teachingPlan.getContent(), TeachingPlanVO.class);
            return vo;
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON反序列化失败: planId={}", planId, e);
            throw new BusinessException("教学设计数据解析失败");
        }
    }
    
    /**
     * 删除教学设计
     */
    @Override
    public void deleteTeachingPlan(Long planId) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();
        
        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }
        
        // 验证权限
        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该教学设计");
        }
        
        // 软删除
        teachingPlan.setIsDeleted(1);
        teachingPlanMapper.updateById(teachingPlan);
        log.info("教学设计删除成功: planId={}, userId={}", planId, userId);
    }
    
    
}

