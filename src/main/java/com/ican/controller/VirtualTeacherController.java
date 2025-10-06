package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.ican.service.VirtualTeacherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 虚拟教师控制器
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Tag(name = "虚拟教师", description = "虚拟教师智能对话、推荐、批改等功能")
@RestController
@RequestMapping("/api/virtual-teacher")
@RequiredArgsConstructor
@SaCheckLogin
public class VirtualTeacherController {
    
    private final VirtualTeacherService virtualTeacherService;
    
    /**
     * 智能对话
     */
    @Operation(summary = "智能对话", description = "与虚拟教师进行智能对话,支持多种角色")
    @PostMapping("/chat")
    public String chat(
            @Parameter(description = "会话ID") @RequestParam String conversationId,
            @Parameter(description = "用户问题") @RequestParam String query,
            @Parameter(description = "角色类型") @RequestParam(defaultValue = "student") String role) {
        
        return virtualTeacherService.chat(conversationId, query, role);
    }
    
    /**
     * 流式对话
     */
    @Operation(summary = "流式对话", description = "流式返回虚拟教师的对话内容")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @Parameter(description = "会话ID") @RequestParam String conversationId,
            @Parameter(description = "用户问题") @RequestParam String query,
            @Parameter(description = "角色类型") @RequestParam(defaultValue = "student") String role) {
        
        return virtualTeacherService.chatStream(conversationId, query, role);
    }
    
    /**
     * 推荐学习内容
     */
    @Operation(summary = "推荐学习内容", description = "基于用户画像推荐学习内容")
    @GetMapping("/recommend")
    public List<String> recommendContent(
            @Parameter(description = "学科") @RequestParam String subject) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        return virtualTeacherService.recommendContent(userId, subject);
    }
    
    /**
     * 批改作业
     */
    @Operation(summary = "批改作业", description = "AI自动批改作业并给出反馈")
    @PostMapping("/grade-assignment")
    public String gradeAssignment(
            @Parameter(description = "作业内容") @RequestParam String assignment,
            @Parameter(description = "学科") @RequestParam String subject) {
        
        return virtualTeacherService.gradeAssignment(assignment, subject);
    }
}

