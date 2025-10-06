package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 功能控制器
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Tag(name = "RAG 功能", description = "检索增强生成相关功能")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@SaCheckLogin
public class RAGController {
    
    private final RAGService ragService;
    
    /**
     * RAG 问答
     */
    @Operation(summary = "RAG 问答", description = "基于知识库的智能问答")
    @PostMapping("/chat")
    public String ragChat(
            @Parameter(description = "会话ID") @RequestParam String conversationId,
            @Parameter(description = "用户问题") @RequestParam String query) {
        
        return ragService.ragChat(conversationId, query);
    }
    
    /**
     * 文档问答
     */
    @Operation(summary = "文档问答", description = "针对特定文档的问答")
    @PostMapping("/document-chat")
    public String documentChat(
            @Parameter(description = "文档ID") @RequestParam Long documentId,
            @Parameter(description = "用户问题") @RequestParam String query) {
        
        return ragService.documentChat(documentId, query);
    }
    
    /**
     * 论文总结 - 结构化输出
     */
    @Operation(summary = "论文总结", description = "自动生成论文结构化总结(研究背景、方法、结果、创新点)")
    @PostMapping("/summarize-paper")
    public PaperSummaryVO summarizePaper(
            @Parameter(description = "论文文档ID") @RequestParam Long documentId) {
        
        return ragService.summarizePaper(documentId);
    }
    
    /**
     * 生成教学设计 - 结构化输出
     */
    @Operation(summary = "生成教学设计", description = "基于参考文档自动生成完整的教学设计方案(目标、过程、评价、作业)")
    @PostMapping("/generate-teaching-plan")
    public TeachingPlanVO generateTeachingPlan(
            @Parameter(description = "课题") @RequestParam String topic,
            @Parameter(description = "学段") @RequestParam String grade,
            @Parameter(description = "学科") @RequestParam String subject,
            @Parameter(description = "参考文档ID列表") @RequestParam(required = false) List<Long> documentIds) {
        
        return ragService.generateTeachingPlan(topic, grade, subject, documentIds);
    }
}

