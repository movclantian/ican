package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.config.ChatConfig;
import com.ican.config.RAGConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.ican.model.dto.ChatRequestDTO;
import com.ican.model.vo.ChatMessageVO;
import com.ican.model.vo.ChatResponseVO;
import com.ican.model.vo.ChatSessionVO;
import com.ican.service.ChatService;
import com.ican.model.entity.ChatMessageDO;
import com.ican.model.entity.ChatSessionDO;
import com.ican.mapper.ChatMessageMapper;
import com.ican.mapper.ChatSessionMapper;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 聊天服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final OpenAiChatModel openAiChatModel;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConfig chatConfig;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponseVO chat(ChatRequestDTO request) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        String conversationId = request.getConversationId();
        boolean isNewSession = StrUtil.isBlank(conversationId);

        // 如果没有提供会话ID,创建新会话
        if (isNewSession) {
            conversationId = createSession(currentUserId, "新对话");
        } else {
            // 验证会话所有权
            validateSessionOwnership(conversationId, currentUserId);
        }

        // 检查是否是该会话的第一条消息（用于判断是否需要生成标题）
        boolean shouldGenerateTitle = shouldGenerateTitle(conversationId);

        String aiText;
        
        // 根据是否启用RAG选择不同的对话方式
        if (Boolean.TRUE.equals(request.getEnableRag())) {
            log.info("使用RAG增强对话: conversationId={}, topK={}", conversationId, request.getRagTopK());
            aiText = chatWithRag(conversationId, request.getMessage(), request.getRagTopK(), currentUserId);
        } else {
            // 原有的普通对话逻辑
            aiText = chatNormal(conversationId, request.getMessage());
        }

        // 保存对话消息
        saveChatMessage(conversationId, "USER", request.getMessage());
        saveChatMessage(conversationId, "ASSISTANT", aiText);

        // 如果是该会话的第一条消息,生成会话标题
        if (shouldGenerateTitle) {
            if (chatConfig.getAsyncTitleGeneration()) {
                generateSessionTitleAsync(conversationId, request.getMessage());
            } else {
                generateAndUpdateSessionTitle(conversationId, request.getMessage());
            }
        }

        return ChatResponseVO.builder()
            .conversationId(conversationId)
            .userMessage(request.getMessage())
            .aiResponse(aiText)
            .timestamp(LocalDateTime.now())
            .build();
    }

    /**
     * 普通对话（无RAG）
     */
    private String chatNormal(String conversationId, String userMessage) {
        // 构建消息列表
        List<Message> messages = new ArrayList<>();

        // 获取历史对话记录
        List<Message> history = getConversationHistory(conversationId);
        if (history != null && !history.isEmpty()) {
            messages.addAll(limitHistoryMessages(history));
        }

        // 添加用户当前消息
        UserMessage userMsg = new UserMessage(userMessage);
        messages.add(userMsg);

        // 构建 Prompt,使用配置的参数
        Prompt prompt = createPrompt(messages);
        ChatResponse chatResponse = openAiChatModel.call(prompt);
        
        // 检查响应是否为空
        if (chatResponse == null || chatResponse.getResult() == null || 
            chatResponse.getResult().getOutput() == null) {
            throw new BusinessException("AI 响应异常,请稍后重试");
        }
        
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        
        // 检查 AI 消息内容
        String aiText = assistantMessage.getText();
        if (aiText == null || aiText.isEmpty()) {
            throw new BusinessException("AI 响应为空,请重新提问");
        }

        // 保存到 ChatMemoryRepository (用于 AI 上下文)
        try {
            chatMemoryRepository.saveAll(conversationId, List.of(userMsg, assistantMessage));
        } catch (Exception e) {
            log.error("保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
        }

        return aiText;
    }

    /**
     * RAG增强对话
     */
    private String chatWithRag(String conversationId, String userMessage, Integer topK, Long userId) {
        // 使用配置的topK或传入的topK
        int ragTopK = topK != null ? topK : ragConfig.getRetrieval().getTopK();
        
        // 构建用户过滤表达式,只检索该用户的文档
        var filterExpression = new FilterExpressionBuilder().eq("userId", userId).build();
        
        // 构建 SearchRequest
        SearchRequest searchRequest = SearchRequest.builder()
            .query(userMessage)  // 使用用户问题作为查询
            .topK(ragTopK)
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .filterExpression(filterExpression)
            .build();
        
        // 使用 QuestionAnswerAdvisor 自动处理 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .build();
        
        // 获取历史对话
        List<Message> history = getConversationHistory(conversationId);
        List<Message> limitedHistory = (history != null && !history.isEmpty()) 
            ? limitHistoryMessages(history) 
            : new ArrayList<>();
        
        // 构建系统提示
        String systemPrompt = "你是一个智能助手。请基于提供的参考文档回答用户问题。" +
            "如果参考文档中没有相关信息,请基于你的知识回答,并说明这不是来自文档。";
        
        // 使用 ChatClient 进行 RAG 对话
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
            .defaultAdvisors(qaAdvisor)  // QuestionAnswerAdvisor 会自动检索文档并增强提示
            .defaultSystem(systemPrompt)
            .defaultOptions(OpenAiChatOptions.builder()
                .temperature(chatConfig.getTemperature())
                .maxTokens(chatConfig.getMaxTokens())
                .build())
            .build();
        
        // 构建包含历史对话的消息列表
        List<Message> messages = new ArrayList<>();
        messages.addAll(limitedHistory);
        messages.add(new UserMessage(userMessage));
        
        // 执行 RAG 对话
        String response = chatClient.prompt()
            .messages(messages)
            .call()
            .content();
        
        if (response == null || response.isEmpty()) {
            throw new BusinessException("RAG对话响应为空,请重新提问");
        }
        
        // 保存到记忆库(保存原始用户消息)
        try {
            UserMessage userMsg = new UserMessage(userMessage);
            AssistantMessage assistantMessage = new AssistantMessage(response);
            chatMemoryRepository.saveAll(conversationId, List.of(userMsg, assistantMessage));
        } catch (Exception e) {
            log.error("保存RAG对话到ChatMemoryRepository失败: conversationId={}", conversationId, e);
        }
        
        log.info("RAG对话完成: conversationId={}, responseLength={}", conversationId, response.length());
        return response;
    }

    @Override
    public SseEmitter chatStream(ChatRequestDTO request) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        
        String conversationId = request.getConversationId();
        boolean isNewSession = StrUtil.isBlank(conversationId);

        // 如果没有提供会话ID,创建新会话
        if (isNewSession) {
            conversationId = createSession(currentUserId, "新对话");
        } else {
            // 验证会话所有权
            validateSessionOwnership(conversationId, currentUserId);
        }

        // 检查是否是该会话的第一条消息（用于判断是否需要生成标题）
        final boolean shouldGenerateTitle = shouldGenerateTitle(conversationId);
        final String finalConversationId = conversationId;

        // 根据是否启用RAG选择不同的流式对话方式
        if (Boolean.TRUE.equals(request.getEnableRag())) {
            log.info("使用RAG增强流式对话: conversationId={}, topK={}", conversationId, request.getRagTopK());
            return chatStreamWithRag(emitter, finalConversationId, request, currentUserId, shouldGenerateTitle);
        } else {
            return chatStreamNormal(emitter, finalConversationId, request, shouldGenerateTitle);
        }
    }

    /**
     * 普通流式对话（无RAG）
     */
    private SseEmitter chatStreamNormal(SseEmitter emitter, String conversationId, 
                                        ChatRequestDTO request, boolean shouldGenerateTitle) {
        // 构建消息列表
        List<Message> messages = new ArrayList<>();

        // 获取历史对话记录 (优先从 ChatMemoryRepository,如果为空则从数据库恢复)
        List<Message> history = getConversationHistory(conversationId);
        if (history != null && !history.isEmpty()) {
            // 限制历史消息数量,防止 Token 超限
            messages.addAll(limitHistoryMessages(history));
        }

        // 添加用户当前消息
        UserMessage userMessage = new UserMessage(request.getMessage());
        messages.add(userMessage);

        // 构建 Prompt,使用配置的参数(流式)
        Prompt prompt = createPrompt(messages);
        
        // 用于累积完整的AI响应
        StringBuilder fullResponse = new StringBuilder();
        
        // 标记是否已保存消息(防止异常时重复保存)
        final boolean[] messageSaved = {false};
        
        // 异步处理流式响应
        openAiChatModel.stream(prompt)
            .map(response -> {
                if (response == null || response.getResult() == null || 
                    response.getResult().getOutput() == null) {
                    return "";
                }
                String content = response.getResult().getOutput().getText();
                if (content != null) {
                    fullResponse.append(content);
                }
                return content != null ? content : "";
            })
            .doOnNext(content -> {
                try {
                    if (!content.isEmpty()) {
                        emitter.send(SseEmitter.event().data(content));
                    }
                } catch (Exception e) {
                    log.error("发送SSE数据失败: conversationId={}", conversationId, e);
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    // 流式传输完成后,保存消息
                    String aiResponse = fullResponse.toString();
                    
                    // 检查 AI 响应是否为空
                    if (aiResponse.isEmpty()) {
                        log.warn("流式对话响应为空: conversationId={}", conversationId);
                        emitter.complete();
                        return;
                    }
                    
                    AssistantMessage assistantMessage = new AssistantMessage(aiResponse);
                    
                    // 1. 先保存到业务数据库
                    saveChatMessage(conversationId, "USER", request.getMessage());
                    saveChatMessage(conversationId, "ASSISTANT", aiResponse);
                    
                    messageSaved[0] = true;
                    
                    // 2. 再保存到 ChatMemoryRepository (用于 AI 上下文)
                    try {
                        chatMemoryRepository.saveAll(conversationId, List.of(userMessage, assistantMessage));
                    } catch (Exception e) {
                        log.error("流式对话-保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
                        // 不影响主流程,下次对话会从数据库重建上下文
                    }
                    
                    // 如果是该会话的第一条消息,生成标题
                    if (shouldGenerateTitle) {
                        if (chatConfig.getAsyncTitleGeneration()) {
                            generateSessionTitleAsync(conversationId, request.getMessage());
                        } else {
                            generateAndUpdateSessionTitle(conversationId, request.getMessage());
                        }
                    }
                    
                    log.info("流式对话完成: conversationId={}, messageLength={}", 
                        conversationId, aiResponse.length());
                    emitter.complete();
                } catch (Exception e) {
                    log.error("完成SSE传输失败: conversationId={}", conversationId, e);
                    emitter.completeWithError(e);
                }
            })
            .doOnError(error -> {
                log.error("流式对话出错: conversationId={}, error={}", 
                    conversationId, error.getMessage(), error);
                
                // 如果消息尚未保存且有部分响应,记录错误但不保存
                if (!messageSaved[0] && fullResponse.length() > 0) {
                    log.warn("流式对话异常中断,已生成部分内容未保存: conversationId={}, partialLength={}", 
                        conversationId, fullResponse.length());
                }
                
                try {
                    emitter.completeWithError(error);
                } catch (Exception e) {
                    log.error("发送错误响应失败: conversationId={}", conversationId, e);
                }
            })
            .subscribe();
        
        return emitter;
    }

    /**
     * RAG增强流式对话
     */
    private SseEmitter chatStreamWithRag(SseEmitter emitter, String conversationId, 
                                         ChatRequestDTO request, Long userId, boolean shouldGenerateTitle) {
        // 使用配置的topK或传入的topK
        int ragTopK = request.getRagTopK() != null ? request.getRagTopK() : ragConfig.getRetrieval().getTopK();
        
        // 构建用户过滤表达式,只检索该用户的文档
        var filterExpression = new FilterExpressionBuilder().eq("userId", userId).build();
        
        // 构建 SearchRequest
        SearchRequest searchRequest = SearchRequest.builder()
            .query(request.getMessage())
            .topK(ragTopK)
            .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
            .filterExpression(filterExpression)
            .build();
        
        // 使用 QuestionAnswerAdvisor 自动处理 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(searchRequest)
            .build();
        
        // 获取历史对话
        List<Message> history = getConversationHistory(conversationId);
        List<Message> limitedHistory = (history != null && !history.isEmpty()) 
            ? limitHistoryMessages(history) 
            : new ArrayList<>();
        
        // 构建系统提示
        String systemPrompt = "你是一个智能助手。请基于提供的参考文档回答用户问题。" +
            "如果参考文档中没有相关信息,请基于你的知识回答,并说明这不是来自文档。";
        
        // 使用 ChatClient 进行 RAG 流式对话
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
            .defaultAdvisors(qaAdvisor)
            .defaultSystem(systemPrompt)
            .defaultOptions(OpenAiChatOptions.builder()
                .temperature(chatConfig.getTemperature())
                .maxTokens(chatConfig.getMaxTokens())
                .build())
            .build();
        
        // 构建包含历史对话的消息列表
        List<Message> messages = new ArrayList<>();
        messages.addAll(limitedHistory);
        messages.add(new UserMessage(request.getMessage()));
        
        // 用于累积完整的AI响应
        StringBuilder fullResponse = new StringBuilder();
        
        // 标记是否已保存消息
        final boolean[] messageSaved = {false};
        
        // 执行 RAG 流式对话
        chatClient.prompt()
            .messages(messages)
            .stream()
            .content()
            .doOnNext(content -> {
                try {
                    if (content != null && !content.isEmpty()) {
                        fullResponse.append(content);
                        emitter.send(SseEmitter.event().data(content));
                    }
                } catch (Exception e) {
                    log.error("RAG流式-发送SSE数据失败: conversationId={}", conversationId, e);
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    String aiResponse = fullResponse.toString();
                    
                    if (aiResponse.isEmpty()) {
                        log.warn("RAG流式对话响应为空: conversationId={}", conversationId);
                        emitter.complete();
                        return;
                    }
                    
                    // 保存到业务数据库
                    saveChatMessage(conversationId, "USER", request.getMessage());
                    saveChatMessage(conversationId, "ASSISTANT", aiResponse);
                    
                    messageSaved[0] = true;
                    
                    // 保存到 ChatMemoryRepository
                    try {
                        UserMessage userMsg = new UserMessage(request.getMessage());
                        AssistantMessage assistantMessage = new AssistantMessage(aiResponse);
                        chatMemoryRepository.saveAll(conversationId, List.of(userMsg, assistantMessage));
                    } catch (Exception e) {
                        log.error("RAG流式-保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
                    }
                    
                    // 生成标题
                    if (shouldGenerateTitle) {
                        if (chatConfig.getAsyncTitleGeneration()) {
                            generateSessionTitleAsync(conversationId, request.getMessage());
                        } else {
                            generateAndUpdateSessionTitle(conversationId, request.getMessage());
                        }
                    }
                    
                    log.info("RAG流式对话完成: conversationId={}, messageLength={}", 
                        conversationId, aiResponse.length());
                    emitter.complete();
                } catch (Exception e) {
                    log.error("RAG流式-完成SSE传输失败: conversationId={}", conversationId, e);
                    emitter.completeWithError(e);
                }
            })
            .doOnError(error -> {
                log.error("RAG流式对话出错: conversationId={}, error={}", 
                    conversationId, error.getMessage(), error);
                
                if (!messageSaved[0] && fullResponse.length() > 0) {
                    log.warn("RAG流式对话异常中断,已生成部分内容未保存: conversationId={}, partialLength={}", 
                        conversationId, fullResponse.length());
                }
                
                try {
                    emitter.completeWithError(error);
                } catch (Exception e) {
                    log.error("发送RAG错误响应失败: conversationId={}", conversationId, e);
                }
            })
            .subscribe();
        
        return emitter;
    }

    @Override
    public List<ChatSessionVO> getUserSessions(Long userId) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        // 如果传入的 userId 不为空且不等于当前用户ID,则抛出异常(防止越权)
        if (userId != null && !userId.equals(currentUserId)) {
            throw new BusinessException("无权访问其他用户的会话");
        }
        
        // 只查询当前用户的会话
        LambdaQueryWrapper<ChatSessionDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSessionDO::getUserId, currentUserId)
               .eq(ChatSessionDO::getIsDeleted, 0)  // 只查询未删除的会话
               .orderByDesc(ChatSessionDO::getUpdateTime);

        List<ChatSessionDO> sessions = chatSessionMapper.selectList(wrapper);
        return sessions.stream().map(this::convertToSessionResp).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageVO> getSessionHistory(String conversationId) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        // 从数据库查询历史消息
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session == null) {
            return new ArrayList<>();
        }
        
        // 验证会话所有权
        if (!session.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权访问该会话");
        }

        List<ChatMessageDO> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageDO>()
            .eq(ChatMessageDO::getSessionId, session.getId())
            .orderByAsc(ChatMessageDO::getCreateTime));

        return messages.stream().map(this::convertToMessageResp).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createSession(Long userId, String title) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        // 如果传入的 userId 不为空且不等于当前用户ID,则抛出异常
        if (userId != null && !userId.equals(currentUserId)) {
            throw new BusinessException("无权为其他用户创建会话");
        }
        
        // 验证标题长度
        if (StrUtil.isNotBlank(title) && title.length() > 100) {
            title = title.substring(0, 100);
        }
        
        // 使用当前用户ID创建会话
        String conversationId = IdUtil.fastSimpleUUID();

        ChatSessionDO session = new ChatSessionDO();
        session.setConversationId(conversationId);
        session.setUserId(currentUserId);
        session.setTitle(StrUtil.isNotBlank(title) ? title : "新对话");
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());

        chatSessionMapper.insert(session);

        log.info("创建新会话: conversationId={}, userId={}", conversationId, currentUserId);
        return conversationId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String conversationId) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null) {
            // 验证会话所有权
            if (!session.getUserId().equals(currentUserId)) {
                throw new BusinessException("无权删除该会话");
            }
            
            // 1. 先删除业务数据(在事务内)
            chatSessionMapper.deleteById(session.getId());
            chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, session.getId()));

            // 2. 再清空 ChatMemoryRepository
            try {
                chatMemoryRepository.deleteByConversationId(conversationId);
            } catch (Exception e) {
                log.error("删除ChatMemoryRepository数据失败: conversationId={}", conversationId, e);
                // 不抛出异常,业务数据已删除即可
            }

            log.info("删除会话: conversationId={}", conversationId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearSessionHistory(String conversationId) {
        // 获取当前登录用户ID
        Long currentUserId = getCurrentUserId();
        
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null) {
            // 验证会话所有权
            if (!session.getUserId().equals(currentUserId)) {
                throw new BusinessException("无权清空该会话历史");
            }
            
            // 1. 先删除业务数据(在事务内)
            chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getSessionId, session.getId()));

            // 2. 再清空 ChatMemoryRepository
            try {
                chatMemoryRepository.deleteByConversationId(conversationId);
            } catch (Exception e) {
                log.error("清空ChatMemoryRepository失败: conversationId={}", conversationId, e);
                // 不抛出异常,业务数据已清空即可
            }

            log.info("清空会话历史: conversationId={}", conversationId);
        }
    }

    /**
     * 判断是否需要为会话生成标题
     * 检查该会话是否还没有任何消息（第一次对话）
     */
    private boolean shouldGenerateTitle(String conversationId) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session == null) {
            return false;
        }

        // 检查数据库中是否已有消息
        Long messageCount = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessageDO>()
            .eq(ChatMessageDO::getSessionId, session.getId()));

        // 如果没有消息，说明这是第一次对话，需要生成标题
        return messageCount == 0;
    }

    /**
     * 保存聊天消息到数据库
     */
    private void saveChatMessage(String conversationId, String role, String content) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null) {
            ChatMessageDO message = new ChatMessageDO();
            message.setSessionId(session.getId());
            message.setRole(role);
            message.setContent(content);
            message.setCreateTime(LocalDateTime.now());

            chatMessageMapper.insert(message);
        }
    }

    /**
     * 使用 AI 生成并更新会话标题
     * 根据用户的第一条消息,让 AI 提取关键内容作为标题
     */
    private void generateAndUpdateSessionTitle(String conversationId, String firstMessage) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null && "新对话".equals(session.getTitle())) {
            try {
                // 构建提示词,让 AI 生成简洁的标题
                String titlePrompt = "请根据以下问题,提取关键内容生成一个简洁的会话标题(不超过15个字,不要有标点符号):\n" + firstMessage;

                List<Message> titleMessages = new ArrayList<>();
                titleMessages.add(new SystemMessage("你是一个标题生成助手,请直接输出标题内容,不要有任何多余的文字和标点符号。"));
                titleMessages.add(new UserMessage(titlePrompt));

                Prompt prompt = new Prompt(titleMessages);
                ChatResponse response = openAiChatModel.call(prompt);
                String generatedTitle = response.getResult().getOutput().getText();
                if (generatedTitle == null) {
                    generatedTitle = "";
                } else {
                    generatedTitle = generatedTitle.trim();
                }

                // 确保标题不超过15个字
                if (generatedTitle.length() > 15) {
                    generatedTitle = generatedTitle.substring(0, 15);
                }

                // 移除可能的引号和标点符号
                generatedTitle = generatedTitle.replaceAll("[\"'。,!?;:、]", "");

                session.setTitle(generatedTitle);
                session.setUpdateTime(LocalDateTime.now());
                chatSessionMapper.updateById(session);

                log.info("AI生成会话标题: conversationId={}, title={}", conversationId, generatedTitle);
            } catch (Exception e) {
                log.error("生成会话标题失败,使用默认标题", e);
                // 如果生成失败,使用默认逻辑
                String title = firstMessage.length() > 15 ? firstMessage.substring(0, 15) + "..." : firstMessage;
                session.setTitle(title);
                session.setUpdateTime(LocalDateTime.now());
                chatSessionMapper.updateById(session);
            }
        }
    }

    /**
     * 获取对话历史
     * 优先从 ChatMemoryRepository 获取,如果为空则从数据库恢复
     * 
     * @param conversationId 会话ID
     * @return 历史消息列表
     */
    private List<Message> getConversationHistory(String conversationId) {
        try {
            // 1. 优先从 ChatMemoryRepository 获取
            List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
            
            // 2. 如果 ChatMemoryRepository 为空,尝试从数据库恢复
            if (history == null || history.isEmpty()) {
                history = recoverHistoryFromDatabase(conversationId);
                
                // 如果从数据库恢复了数据,同步到 ChatMemoryRepository
                if (history != null && !history.isEmpty()) {
                    try {
                        chatMemoryRepository.saveAll(conversationId, history);
                        log.info("从数据库恢复上下文到ChatMemoryRepository: conversationId={}, messageCount={}", 
                            conversationId, history.size());
                    } catch (Exception e) {
                        log.error("恢复上下文到ChatMemoryRepository失败: conversationId={}", conversationId, e);
                    }
                }
            }
            
            return history;
        } catch (Exception e) {
            log.error("获取对话历史失败: conversationId={}", conversationId, e);
            // 发生异常时尝试从数据库恢复
            return recoverHistoryFromDatabase(conversationId);
        }
    }
    
    /**
     * 从数据库恢复对话历史
     * 
     * @param conversationId 会话ID
     * @return 历史消息列表
     */
    private List<Message> recoverHistoryFromDatabase(String conversationId) {
        try {
            ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
                .eq(ChatSessionDO::getConversationId, conversationId));
            
            if (session == null) {
                return new ArrayList<>();
            }
            
            List<ChatMessageDO> dbMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                    .eq(ChatMessageDO::getSessionId, session.getId())
                    .orderByAsc(ChatMessageDO::getCreateTime)
            );
            
            if (dbMessages == null || dbMessages.isEmpty()) {
                return new ArrayList<>();
            }
            
            // 转换为 Spring AI 的 Message 对象
            List<Message> messages = new ArrayList<>();
            for (ChatMessageDO dbMsg : dbMessages) {
                Message message = switch (dbMsg.getRole()) {
                    case "USER" -> new UserMessage(dbMsg.getContent());
                    case "ASSISTANT" -> new AssistantMessage(dbMsg.getContent());
                    case "SYSTEM" -> new SystemMessage(dbMsg.getContent());
                    default -> null;
                };
                if (message != null) {
                    messages.add(message);
                }
            }
            
            log.debug("从数据库恢复对话历史: conversationId={}, messageCount={}", 
                conversationId, messages.size());
            return messages;
        } catch (Exception e) {
            log.error("从数据库恢复对话历史失败: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 创建 Prompt,使用配置的参数
     * 
     * @param messages 消息列表
     * @return Prompt 对象
     */
    private Prompt createPrompt(List<Message> messages) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .temperature(chatConfig.getTemperature())
            .maxTokens(chatConfig.getMaxTokens())
            .build();
        
        return new Prompt(messages, options);
    }
    
    /**
     * 限制历史消息数量,防止 Token 超限
     * 
     * @param history 历史消息列表
     * @return 限制后的消息列表
     */
    private List<Message> limitHistoryMessages(List<Message> history) {
        int maxHistoryMessages = chatConfig.getMaxHistoryMessages();
        
        if (history.size() <= maxHistoryMessages) {
            return history;
        }
        
        // 只保留最近的消息
        List<Message> limitedHistory = history.subList(
            history.size() - maxHistoryMessages, 
            history.size()
        );
        
        log.debug("历史消息数量限制: 原始={}, 限制后={}", history.size(), limitedHistory.size());
        return limitedHistory;
    }
    
    
    /**
     * 异步生成并更新会话标题
     * 
     * @param conversationId 会话ID
     * @param firstMessage 第一条消息
     */
    private void generateSessionTitleAsync(String conversationId, String firstMessage) {
        // 使用异步方式生成标题,不阻塞主流程
        try {
            // 这里直接调用同步方法,Spring 的 @Async 需要在配置中开启
            // 为了简化实现,使用 CompletableFuture
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    generateAndUpdateSessionTitle(conversationId, firstMessage);
                } catch (Exception e) {
                    log.error("异步生成会话标题失败: conversationId={}", conversationId, e);
                }
            });
        } catch (Exception e) {
            log.error("启动异步标题生成失败: conversationId={}", conversationId, e);
        }
    }
    
    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException("未登录");
        }
        return StpUtil.getLoginIdAsLong();
    }
    
    /**
     * 验证会话所有权
     */
    private void validateSessionOwnership(String conversationId, Long userId) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));
        
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
    }
    
    /**
     * 转换为会话响应
     */
    private ChatSessionVO convertToSessionResp(ChatSessionDO session) {
        return ChatSessionVO.builder()
            .id(session.getId())
            .conversationId(session.getConversationId())
            .title(session.getTitle())
            .createTime(session.getCreateTime())
            .updateTime(session.getUpdateTime())
            .build();
    }

    /**
     * 转换为消息响应
     */
    private ChatMessageVO convertToMessageResp(ChatMessageDO message) {
        return ChatMessageVO.builder()
            .id(message.getId())
            .role(message.getRole())
            .content(message.getContent())
            .createTime(message.getCreateTime())
            .build();
    }
}
