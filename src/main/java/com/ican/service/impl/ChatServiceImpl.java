package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 聊天服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatMemory chatMemoryRepository;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final VectorStore vectorStore;
    
    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;
    
    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private Integer maxTokens;
    
    @Value("${chat.async-title-generation}")
    private Boolean asyncTitleGeneration;
    
    @Value("${search.retrieval.similarity-threshold}")
    private Double defaultSimilarityThreshold;



    // 注入 ChatClient Bean（避免每次创建）
    private final ChatClient normalChatClient;
    private final ChatClient ragChatClient;

    // 注入提示词模板
    private final String sessionTitlePromptTemplate;

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

        // 先保存用户消息，确保失败仍能看到记录
        saveChatMessage(conversationId, "USER", request.getMessage());

        String aiText;
        // 根据是否启用RAG选择不同的对话方式
        if (Boolean.TRUE.equals(request.getEnableRag())) {
            log.info("使用RAG增强对话: conversationId={}, topK={}", conversationId, request.getRagTopK());
            aiText = chatWithRag(conversationId, request, currentUserId);
        } else {
            aiText = chatNormal(conversationId, request);
        }

        // 保存 AI 回复
        saveChatMessage(conversationId, "ASSISTANT", aiText);

        // 如果是该会话的第一条消息,生成会话标题
        if (shouldGenerateTitle) {
            if (asyncTitleGeneration) {
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
     * 普通对话（无RAG）- 支持动态 OpenAiChatOptions
     */
    private String chatNormal(String conversationId, ChatRequestDTO request) {
        String userMessage = request.getMessage();
        
        // 构建自定义 OpenAiChatOptions（如果提供）
        OpenAiChatOptions customOptions = buildCustomOptions(request);
        
        // 构建消息列表
        List<Message> messages = new ArrayList<>();
        
        // 根据是否启用记忆来决定是否加载历史消息
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            messages.addAll(chatMemoryRepository.get(conversationId));
            log.debug("普通对话 - 加载历史消息: conversationId={}, historyCount={}", 
                conversationId, messages.size());
        } else {
            log.debug("普通对话 - 跳过历史消息加载: conversationId={}", conversationId);
        }

        // 添加用户当前消息
        UserMessage userMsg = new UserMessage(userMessage);
        messages.add(userMsg);
        Prompt prompt = new Prompt(messages, customOptions);
        String aiText = normalChatClient.prompt(prompt)
                .call()
                .content();

        // 检查 AI 消息内容
        if (aiText == null || aiText.isEmpty()) {
            throw new BusinessException("AI 响应为空,请重新提问");
        }

        // 保存到 ChatMemoryRepository (用于 AI 上下文) - 仅在启用记忆时保存
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            try {
                AssistantMessage assistantMessage = new AssistantMessage(aiText);
                chatMemoryRepository.add(conversationId, List.of(userMsg, assistantMessage));
                log.debug("普通对话 - 保存到记忆库: conversationId={}", conversationId);
            } catch (Exception e) {
                log.error("保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
            }
        }

        log.info("普通对话完成: conversationId={}, responseLength={}, customOptions={}", 
            conversationId, aiText.length(), customOptions != null);
        return aiText;
    }

    /**
     * RAG增强对话 - 支持动态 OpenAiChatOptions 和 SearchRequest
     */
    private String chatWithRag(String conversationId, ChatRequestDTO request, Long userId) {
        String userMessage = request.getMessage();
        
        // 构建自定义 OpenAiChatOptions 和 SearchRequest
        OpenAiChatOptions customOptions = buildCustomOptions(request);
        SearchRequest customSearchRequest = buildCustomSearchRequest(request);

        // 创建针对当前查询的 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(customSearchRequest)
                .build();

        // 构建包含历史对话的消息列表
        List<Message> messages = new ArrayList<>();
        
        // 根据是否启用记忆来决定是否加载历史消息
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            messages.addAll(chatMemoryRepository.get(conversationId));
            log.debug("RAG对话 - 加载历史消息: conversationId={}, historyCount={}", 
                conversationId, messages.size());
        } else {
            log.debug("RAG对话 - 跳过历史消息加载: conversationId={}", conversationId);
        }
        
        // 添加用户当前消息
        messages.add(new UserMessage(userMessage));

        Prompt prompt = new Prompt(messages, customOptions);
        String response = ragChatClient.prompt(prompt)
                .advisors(qaAdvisor) // 动态添加用户过滤的 Advisor
                .call()
                .content();
        

        if (response == null || response.isEmpty()) {
            throw new BusinessException("RAG对话响应为空,请重新提问");
        }

        // 保存到记忆库 - 仅在启用记忆时保存
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            try {
                UserMessage userMsg = new UserMessage(userMessage);
                AssistantMessage assistantMessage = new AssistantMessage(response);
                chatMemoryRepository.add(conversationId, List.of(userMsg, assistantMessage));
                log.debug("RAG对话 - 保存到记忆库: conversationId={}", conversationId);
            } catch (Exception e) {
                log.error("保存RAG对话到ChatMemoryRepository失败: conversationId={}", conversationId, e);
            }
        }

        log.info("RAG对话完成: conversationId={}, responseLength={}, customOptions={}", 
            conversationId, response.length(), customOptions != null);
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
     * 普通流式对话（无RAG）- 支持动态 OpenAiChatOptions
     */
    private SseEmitter chatStreamNormal(SseEmitter emitter, String conversationId,
                                        ChatRequestDTO request, boolean shouldGenerateTitle) {
        // 构建自定义 OpenAiChatOptions（如果提供）
        OpenAiChatOptions customOptions = buildCustomOptions(request);
        
        // 构建消息列表
        List<Message> messages = new ArrayList<>();
        
        // 根据是否启用记忆来决定是否加载历史消息
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            messages.addAll(chatMemoryRepository.get(conversationId));
            log.debug("普通流式对话 - 加载历史消息: conversationId={}, historyCount={}", 
                conversationId, messages.size());
        } else {
            log.debug("普通流式对话 - 跳过历史消息加载: conversationId={}", conversationId);
        }

        // 添加用户当前消息并先持久化用户消息
        UserMessage userMessage = new UserMessage(request.getMessage());
        messages.add(userMessage);
        saveChatMessage(conversationId, "USER", request.getMessage());

        // 用于累积完整的AI响应
        StringBuilder fullResponse = new StringBuilder();

        // 标记是否已保存消息(防止异常时重复保存)
        final boolean[] messageSaved = {false};
        Prompt prompt = new Prompt(messages, customOptions);
        // 异步处理流式响应
        normalChatClient.prompt(prompt)
                .stream()
                .content()
                .map(content -> {
                    if (content != null) {
                        fullResponse.append(content);
                    }
                    return content != null ? content : "";
                })
                .doOnNext(content -> {
                    try {
                        if (!content.isEmpty()) {
                            Map<String, Object> data = Map.of(
                                    "delta", content
                            );
                            // 自动处理所有转义
                            emitter.send(SseEmitter.event().data(data));
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
                        // 用户消息已保存，这里只保存 AI 回复
                        saveChatMessage(conversationId, "ASSISTANT", aiResponse);

                        messageSaved[0] = true;

                        // 2. 再保存到 ChatMemoryRepository (用于 AI 上下文) - 仅在启用记忆时保存
                        if (Boolean.TRUE.equals(request.getEnableMemory())) {
                            try {
                                chatMemoryRepository.add(conversationId, List.of(userMessage, assistantMessage));
                                log.debug("普通流式对话 - 保存到记忆库: conversationId={}", conversationId);
                            } catch (Exception e) {
                                log.error("流式对话-保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
                                // 不影响主流程,下次对话会从数据库重建上下文
                            }
                        }

                        // 如果是该会话的第一条消息,生成标题
                        if (shouldGenerateTitle) {
                            if (asyncTitleGeneration) {
                                generateSessionTitleAsync(conversationId, request.getMessage());
                            } else {
                                generateAndUpdateSessionTitle(conversationId, request.getMessage());
                            }
                        }

                        log.info("流式对话完成: conversationId={}, messageLength={}",
                                conversationId, aiResponse.length());
                        // 发送OpenAI格式的完成标记（不要手动包含 "data: " 前缀或额外换行，Spring 会自动封装）
                        emitter.send(SseEmitter.event().data("[DONE]"));
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
                    if (!messageSaved[0] && !fullResponse.isEmpty()) {
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
     * RAG增强流式对话 - 支持动态 OpenAiChatOptions 和 SearchRequest
     */
    private SseEmitter chatStreamWithRag(SseEmitter emitter, String conversationId,
                                         ChatRequestDTO request, Long userId, boolean shouldGenerateTitle) {
        // 构建自定义 OpenAiChatOptions 和 SearchRequest
        OpenAiChatOptions customOptions = buildCustomOptions(request);
        SearchRequest customSearchRequest = buildCustomSearchRequest(request);

        // 使用 QuestionAnswerAdvisor 自动处理 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(customSearchRequest)
                .build();
                
        // 构建包含历史对话的消息列表
        List<Message> messages = new ArrayList<>();
        
        // 根据是否启用记忆来决定是否加载历史消息
        if (Boolean.TRUE.equals(request.getEnableMemory())) {
            messages.addAll(chatMemoryRepository.get(conversationId));
            log.debug("RAG流式对话 - 加载历史消息: conversationId={}, historyCount={}", 
                conversationId, messages.size());
        } else {
            log.debug("RAG流式对话 - 跳过历史消息加载: conversationId={}", conversationId);
        }
        messages.add(new UserMessage(request.getMessage()));
        // 提前保存用户消息
        saveChatMessage(conversationId, "USER", request.getMessage());

        // 用于累积完整的AI响应
        StringBuilder fullResponse = new StringBuilder();

        // 标记是否已保存消息
        final boolean[] messageSaved = {false};

        Prompt prompt = new Prompt(messages, customOptions);
        // 异步处理流式响应
        ragChatClient.prompt(prompt)
                .advisors(qaAdvisor) // 动态添加用户过滤的 Advisor
                .stream()
                .content()
                .doOnNext(content -> {
                    try {
                        if (content != null && !content.isEmpty()) {
                            fullResponse.append(content);
                            // 构建数据结构
                            Map<String, Object> data = Map.of(
                                    "delta", content
                            );
                            // 自动处理所有转义
                            emitter.send(SseEmitter.event().data(data));
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
                        // 用户消息已保存，只保存 AI 回复
                        saveChatMessage(conversationId, "ASSISTANT", aiResponse);

                        messageSaved[0] = true;

                        // 保存到 ChatMemoryRepository - 仅在启用记忆时保存
                        if (Boolean.TRUE.equals(request.getEnableMemory())) {
                            try {
                                UserMessage userMsg = new UserMessage(request.getMessage());
                                AssistantMessage assistantMessage = new AssistantMessage(aiResponse);
                                chatMemoryRepository.add(conversationId, List.of(userMsg, assistantMessage));
                                log.debug("RAG流式对话 - 保存到记忆库: conversationId={}", conversationId);
                            } catch (Exception e) {
                                log.error("RAG流式-保存到ChatMemoryRepository失败: conversationId={}", conversationId, e);
                            }
                        }

                        // 生成标题
                        if (shouldGenerateTitle) {
                            if (asyncTitleGeneration) {
                                generateSessionTitleAsync(conversationId, request.getMessage());
                            } else {
                                generateAndUpdateSessionTitle(conversationId, request.getMessage());
                            }
                        }

                        log.info("RAG流式对话完成: conversationId={}, messageLength={}",
                                conversationId, aiResponse.length());
                        // 发送OpenAI格式的完成标记（不要手动包含 "data: " 前缀或额外换行，Spring 会自动封装）
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("RAG流式-完成SSE传输失败: conversationId={}", conversationId, e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(error -> {
                    log.error("RAG流式对话出错: conversationId={}, error={}",
                            conversationId, error.getMessage(), error);

                    if (!messageSaved[0] && !fullResponse.isEmpty()) {
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
                .eq(ChatSessionDO::getIsDeleted, 0) // 只查询未删除的会话
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
                chatMemoryRepository.clear(conversationId);
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
                chatMemoryRepository.clear(conversationId);
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
     * 优化：使用提示词模板，代码更简洁
     */
    private void generateAndUpdateSessionTitle(String conversationId, String firstMessage) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
                .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null && "新对话".equals(session.getTitle())) {
            try {
                // 使用提示词模板生成标题
                String userPrompt = sessionTitlePromptTemplate.replace("{message}", firstMessage);

                // 使用 normalChatClient 生成标题
                String generatedTitle = normalChatClient.prompt()
                        .user(userPrompt)
                        .call()
                        .content();

                if (generatedTitle != null) {
                    generatedTitle = generatedTitle.trim();

                    // 确保标题不超过15个字
                    if (generatedTitle.length() > 15) {
                        generatedTitle = generatedTitle.substring(0, 15);
                    }

                    // 移除可能的引号和标点符号
                    generatedTitle = generatedTitle.replaceAll("[\"'。,!?;:、]", "");

                    if (!generatedTitle.isEmpty()) {
                        session.setTitle(generatedTitle);
                        session.setUpdateTime(LocalDateTime.now());
                        chatSessionMapper.updateById(session);

                        log.info("AI生成会话标题: conversationId={}, title={}", conversationId, generatedTitle);
                        return;
                    }
                }

                // 如果生成的标题为空，使用默认逻辑
                useFallbackTitle(session, firstMessage);
            } catch (Exception e) {
                log.error("生成会话标题失败,使用默认标题: conversationId={}", conversationId, e);
                useFallbackTitle(session, firstMessage);
            }
        }
    }

    /**
     * 使用默认逻辑生成标题（后备方案）
     */
    private void useFallbackTitle(ChatSessionDO session, String firstMessage) {
        String title = firstMessage.length() > 15 ? firstMessage.substring(0, 15) + "..." : firstMessage;
        session.setTitle(title);
        session.setUpdateTime(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }


    /**
     * 异步生成并更新会话标题
     *
     * @param conversationId 会话ID
     * @param firstMessage   第一条消息
     */
    @Async
    protected void generateSessionTitleAsync(String conversationId, String firstMessage) {
        try {
            generateAndUpdateSessionTitle(conversationId, firstMessage);
        } catch (Exception e) {
            log.error("异步生成会话标题失败: conversationId={}", conversationId, e);
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
     * 转换聊天消息响应
     */
    private ChatMessageVO convertToMessageResp(ChatMessageDO message) {
        return ChatMessageVO.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .createTime(message.getCreateTime())
                .build();
    }

    /**
     * 构建自定义OpenAI聊天选项
     * 根据请求参数动态覆盖默认配置
     * 只在有自定义参数时才返回，否则返回null使用默认客户端
     */
    private OpenAiChatOptions buildCustomOptions(ChatRequestDTO request) {
        // 检查是否有任何自定义参数
        boolean hasCustomOptions = request.getCustomTemperature() != null || 
                                  request.getCustomMaxTokens() != null;
        
        // 如果没有自定义参数，返回null，使用ChatClientConfig中的默认客户端
        if (!hasCustomOptions) {
            return null;
        }
        
        // 基于 ChatClientConfig 中的默认配置构建自定义选项
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        
        // 动态设置温度参数，优先使用自定义值，否则使用默认配置
        if (request.getCustomTemperature() != null) {
            builder = builder.temperature(request.getCustomTemperature());
        } else {
            builder = builder.temperature(temperature);
        }
        
        // 动态设置最大Token数，优先使用自定义值，否则使用默认配置
        if (request.getCustomMaxTokens() != null) {
            builder = builder.maxTokens(request.getCustomMaxTokens());
        } else {
            builder = builder.maxTokens(maxTokens);
        }
        
        return builder.build();
    }

    /**
     * 构建自定义搜索请求(RAG时使用)
     * 根据请求参数动态设置相似度阈值和检索数量
     */
    private SearchRequest buildCustomSearchRequest(ChatRequestDTO request) {
        // 使用正确的 API - 基于 ChatClientConfig 中的用法
        SearchRequest.Builder builder = SearchRequest.builder();
        
        // 设置检索数量
        builder = builder.topK(request.getRagTopK() != null ? request.getRagTopK() : 5);
        
        // 动态设置相似度阈值
        if (request.getCustomSimilarityThreshold() != null) {
            builder = builder.similarityThreshold(request.getCustomSimilarityThreshold());
        } else {
            // 使用默认配置
            builder = builder.similarityThreshold(defaultSimilarityThreshold);
        }
        
        return builder.build();
    }


}
