package com.ican.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 虚拟教师服务接口
 * 
 * @author ICan
 * @since 2024-10-06
 */
public interface VirtualTeacherService {
    
    /**
     * 智能对话
     * 
     * @param conversationId 会话ID
     * @param query 用户问题
     * @param role 用户角色 (teacher, student, researcher)
     * @return AI回复
     */
    String chat(String conversationId, String query, String role);
    
    /**
     * 流式对话
     * 
     * @param conversationId 会话ID
     * @param query 用户问题
     * @param role 用户角色
     * @return SSE流
     */
    SseEmitter chatStream(String conversationId, String query, String role);
    
    /**
     * 推荐学习内容
     * 
     * @param userId 用户ID
     * @param subject 学科
     * @return 推荐内容列表
     */
    java.util.List<String> recommendContent(Long userId, String subject);
    
    /**
     * 批改作业
     * 
     * @param assignment 作业内容
     * @param subject 学科
     * @return 批改结果
     */
    String gradeAssignment(String assignment, String subject);
}

