package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import com.ican.service.VirtualTeacherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟教师服务实现
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualTeacherServiceImpl implements VirtualTeacherService {
    
    private final OpenAiChatModel openAiChatModel;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    
    @Override
    public String chat(String conversationId, String query, String role) {
        log.info("虚拟教师对话: conversationId={}, role={}, query={}", conversationId, query, role);
        
        // 获取对话历史
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history == null) {
            history = new ArrayList<>();
        }
        
        // 构建消息列表
        List<Message> messages = new ArrayList<>();
        
        // 添加系统提示词(根据角色定制)
        messages.add(new SystemMessage(getRoleSystemPrompt(role)));
        
        // 添加历史消息(限制数量)
        if (!history.isEmpty()) {
            int maxHistory = 10;
            if (history.size() > maxHistory) {
                history = history.subList(history.size() - maxHistory, history.size());
            }
            messages.addAll(history);
        }
        
        // 添加用户当前消息
        UserMessage userMessage = new UserMessage(query);
        messages.add(userMessage);
        
        // 构建Prompt
        Prompt prompt = new Prompt(messages);
        
        // 调用AI模型
        String response = openAiChatModel.call(prompt).getResult().getOutput().getText();
        
        // 保存对话历史
        try {
            AssistantMessage assistantMessage = new AssistantMessage(response);
            chatMemoryRepository.saveAll(conversationId, List.of(userMessage, assistantMessage));
        } catch (Exception e) {
            log.warn("保存虚拟教师对话历史失败: conversationId={}", conversationId, e);
        }
        
        log.info("虚拟教师对话完成: conversationId={}, responseLength={}", conversationId, response.length());
        return response;
    }
    
    @Override
    public SseEmitter chatStream(String conversationId, String query, String role) {
        SseEmitter emitter = new SseEmitter(300000L);
        
        log.info("虚拟教师流式对话: conversationId={}, role={}", conversationId, role);
        
        // 获取对话历史
        List<Message> history = chatMemoryRepository.findByConversationId(conversationId);
        if (history == null) {
            history = new ArrayList<>();
        }
        
        // 构建消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(getRoleSystemPrompt(role)));
        
        if (!history.isEmpty()) {
            int maxHistory = 10;
            if (history.size() > maxHistory) {
                history = history.subList(history.size() - maxHistory, history.size());
            }
            messages.addAll(history);
        }
        
        UserMessage userMessage = new UserMessage(query);
        messages.add(userMessage);
        
        // 流式响应
        StringBuilder fullResponse = new StringBuilder();
        
        openAiChatModel.stream(new Prompt(messages))
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
                    log.error("发送虚拟教师SSE数据失败", e);
                    emitter.completeWithError(e);
                }
            })
            .doOnComplete(() -> {
                try {
                    // 保存对话历史
                    String aiResponse = fullResponse.toString();
                    AssistantMessage assistantMessage = new AssistantMessage(aiResponse);
                    chatMemoryRepository.saveAll(conversationId, List.of(userMessage, assistantMessage));
                    
                    log.info("虚拟教师流式对话完成: conversationId={}", conversationId);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("虚拟教师流式对话完成处理失败", e);
                    emitter.completeWithError(e);
                }
            })
            .doOnError(error -> {
                log.error("虚拟教师流式对话错误", error);
                emitter.completeWithError(error);
            })
            .subscribe();
        
        return emitter;
    }
    
    @Override
    public List<String> recommendContent(Long userId, String subject) {
        log.info("推荐学习内容: userId={}, subject={}", userId, subject);
        
        String prompt = String.format("""
            请为学习 %s 的用户推荐5个值得深入学习的主题或前沿技术。
            
            要求:
            1. 内容要前沿且实用
            2. 难度循序渐进
            3. 每个推荐包含简短说明(50字以内)
            
            请直接列出推荐内容。
            """, subject);
        
        Prompt promptObj = new Prompt(prompt);
        String response = openAiChatModel.call(promptObj).getResult().getOutput().getText();
        
        // 简单分割,实际可用结构化输出
        List<String> recommendations = List.of(response.split("\n"));
        
        return recommendations.stream()
            .filter(s -> !s.trim().isEmpty())
            .limit(5)
            .toList();
    }
    
    @Override
    public String gradeAssignment(String assignment, String subject) {
        log.info("批改作业: subject={}, assignmentLength={}", subject, assignment.length());
        
        String prompt = String.format("""
            你是一位专业的 %s 教师,请批改以下作业:
            
            作业内容:
            %s
            
            请从以下方面进行评价:
            1. 内容准确性
            2. 逻辑完整性
            3. 创新性
            4. 改进建议
            
            请给出详细的批改意见。
            """, subject, assignment);
        
        Prompt promptObj = new Prompt(prompt);
        String response = openAiChatModel.call(promptObj).getResult().getOutput().getText();
        
        log.info("作业批改完成: assignmentLength={}, feedbackLength={}", 
            assignment.length(), response.length());
        
        return response;
    }
    
    /**
     * 根据角色获取系统提示词
     */
    private String getRoleSystemPrompt(String role) {
        return switch (StrUtil.isBlank(role) ? "student" : role.toLowerCase()) {
            case "teacher" -> """
                你是一位经验丰富的教育专家和虚拟教师助手。
                你的职责是:
                1. 帮助教师设计教学方案
                2. 提供教学方法建议
                3. 分析教学效果
                4. 推荐教学资源
                
                请以专业、友好的态度提供帮助。
                """;
            case "student" -> """
                你是一位耐心细致的虚拟教师。
                你的职责是:
                1. 回答学生的学习问题
                2. 引导学生思考
                3. 提供学习建议
                4. 鼓励学生探索
                
                请用简单易懂的语言解释,循循善诱。
                """;
            case "researcher" -> """
                你是一位学术研究助手和虚拟导师。
                你的职责是:
                1. 帮助分析论文和文献
                2. 提供研究方法建议
                3. 协助课题设计
                4. 推荐学术资源
                
                请保持学术严谨性和专业性。
                """;
            default -> """
                你是一位专业的AI助手。
                请根据用户的问题提供准确、有帮助的回答。
                """;
        };
    }
}

