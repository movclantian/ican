package com.ican.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 聊天服务实现
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private JdbcChatMemoryRepository chatMemoryRepository;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponseVO chat(ChatRequestDTO request) {
        String conversationId = request.getConversationId();
        boolean isNewSession = StrUtil.isBlank(conversationId);

        // 如果没有提供会话ID,创建新会话
        if (isNewSession) {
            conversationId = createSession(null, "新对话");
        }

        // 检查是否是该会话的第一条消息（用于判断是否需要生成标题）
        boolean shouldGenerateTitle = shouldGenerateTitle(conversationId);

        // 构建消息列表
        List<Message> messages = new ArrayList<>();

        // 获取历史对话记录 (ChatMemoryRepository 接口方法)
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 添加用户当前消息
        UserMessage userMessage = new UserMessage(request.getMessage());
        messages.add(userMessage);

        // 调用 AI 模型
        Prompt prompt = new Prompt(messages);
        ChatResponse chatResponse = openAiChatModel.call(prompt);
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();

        // 保存用户消息和 AI 回复到 ChatMemoryRepository
        chatMemoryRepository.saveAll(conversationId, List.of(userMessage, assistantMessage));

        // 保存到数据库
        saveChatMessage(conversationId, "USER", request.getMessage());
        saveChatMessage(conversationId, "ASSISTANT", assistantMessage.getText());

        // 如果是该会话的第一条消息,使用 AI 生成会话标题
        if (shouldGenerateTitle) {
            generateAndUpdateSessionTitle(conversationId, request.getMessage());
        }

        return ChatResponseVO.builder()
            .conversationId(conversationId)
            .userMessage(request.getMessage())
            .aiResponse(assistantMessage.getText())
            .timestamp(LocalDateTime.now())
            .build();
    }

    @Override
    public SseEmitter chatStream(ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        
        String conversationId = request.getConversationId();
        boolean isNewSession = StrUtil.isBlank(conversationId);

        // 如果没有提供会话ID,创建新会话
        if (isNewSession) {
            conversationId = createSession(null, "新对话");
        }

        // 检查是否是该会话的第一条消息（用于判断是否需要生成标题）
        final boolean shouldGenerateTitle = shouldGenerateTitle(conversationId);

        // 构建消息列表
        List<Message> messages = new ArrayList<>();

        // 获取历史对话记录
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 添加用户当前消息
        UserMessage userMessage = new UserMessage(request.getMessage());
        messages.add(userMessage);

        // 调用 AI 模型(流式)
        Prompt prompt = new Prompt(messages);
        
        final String finalConversationId = conversationId;
        
        // 用于累积完整的AI响应
        StringBuilder fullResponse = new StringBuilder();
        
        // 异步处理流式响应
        openAiChatModel.stream(prompt)
            .map(response -> {
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
                    log.error("发送SSE数据失败", e);
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    // 流式传输完成后,保存消息
                    String aiResponse = fullResponse.toString();
                    AssistantMessage assistantMessage = new AssistantMessage(aiResponse);
                    
                    // 保存到 ChatMemoryRepository
                    chatMemoryRepository.saveAll(finalConversationId, List.of(userMessage, assistantMessage));
                    
                    // 保存到数据库
                    saveChatMessage(finalConversationId, "USER", request.getMessage());
                    saveChatMessage(finalConversationId, "ASSISTANT", aiResponse);
                    
                    // 如果是该会话的第一条消息,生成标题
                    if (shouldGenerateTitle) {
                        generateAndUpdateSessionTitle(finalConversationId, request.getMessage());
                    }
                    
                    log.info("流式对话完成: conversationId={}, messageLength={}", finalConversationId, aiResponse.length());
                    emitter.complete();
                } catch (Exception e) {
                    log.error("完成SSE传输失败", e);
                    emitter.completeWithError(e);
                }
            })
            .doOnError(error -> {
                log.error("流式对话出错: conversationId={}, error={}", finalConversationId, error.getMessage(), error);
                emitter.completeWithError(error);
            })
            .subscribe();
        
        return emitter;
    }

    @Override
    public List<ChatSessionVO> getUserSessions(Long userId) {
        LambdaQueryWrapper<ChatSessionDO> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(ChatSessionDO::getUserId, userId);
        }
        wrapper.orderByDesc(ChatSessionDO::getUpdateTime);

        List<ChatSessionDO> sessions = chatSessionMapper.selectList(wrapper);
        return sessions.stream().map(this::convertToSessionResp).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageVO> getSessionHistory(String conversationId) {
        // 从数据库查询历史消息
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session == null) {
            return new ArrayList<>();
        }

        List<ChatMessageDO> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageDO>()
            .eq(ChatMessageDO::getSessionId, session.getId())
            .orderByAsc(ChatMessageDO::getCreateTime));

        return messages.stream().map(this::convertToMessageResp).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createSession(Long userId, String title) {
        String conversationId = IdUtil.fastSimpleUUID();

        ChatSessionDO session = new ChatSessionDO();
        session.setConversationId(conversationId);
        session.setUserId(userId);
        session.setTitle(StrUtil.isNotBlank(title) ? title : "新对话");
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());

        chatSessionMapper.insert(session);

        log.info("创建新会话: conversationId={}, userId={}", conversationId, userId);
        return conversationId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String conversationId) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null) {
            // 删除会话
            chatSessionMapper.deleteById(session.getId());

            // 删除消息
            chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageDO>().eq(ChatMessageDO::getSessionId, session
                .getId()));

            // 清空 ChatMemoryRepository
            chatMemoryRepository.deleteByConversationId(conversationId);

            log.info("删除会话: conversationId={}", conversationId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearSessionHistory(String conversationId) {
        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
            .eq(ChatSessionDO::getConversationId, conversationId));

        if (session != null) {
            // 删除消息
            chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageDO>().eq(ChatMessageDO::getSessionId, session
                .getId()));

            // 清空 ChatMemoryRepository
            chatMemoryRepository.deleteByConversationId(conversationId);

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
