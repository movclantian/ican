package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ican.model.vo.RagChatResultVO;
import com.ican.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * RAG 对话控制器
 * 
 * <p>专注于 RAG 对话功能，论文分析相关接口已迁移至 DocumentController</p>
 * 
 * @author 席崇援
 * @since 2024-10-06
 */
@Slf4j
@Tag(name = "RAG 对话", description = "检索增强生成对话功能")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@SaCheckLogin
public class RAGController {
    
    private final RAGService ragService;
    
    /**
     * RAG 问答（带引用）
     */
    @Operation(summary = "RAG 问答", description = "基于当前用户私有文档向量检索 + 重排序生成回答，并返回引用片段元信息")
    @PostMapping("/chat")
    public RagChatResultVO ragChat(
            @Parameter(description = "会话ID") @RequestParam String conversationId,
            @Parameter(description = "用户问题") @RequestParam String query) {
        
        return ragService.ragChat(conversationId, query);
    }
    
    /**
     * 文档问答（带引用）
     */
    @Operation(summary = "文档问答", description = "限定单一文档上下文进行问答，结果附带引用片段")
    @PostMapping("/document-chat")
    public RagChatResultVO documentChat(
            @Parameter(description = "文档ID") @RequestParam Long documentId,
            @Parameter(description = "用户问题") @RequestParam String query) {
        
        return ragService.documentChat(documentId, query);
    }
    
    /**
     * 知识库问答（带引用）
     */
    @Operation(summary = "知识库问答", description = "在整个知识库的所有文档中进行检索问答，结果附带引用片段")
    @PostMapping("/knowledge-base-chat")
    public RagChatResultVO knowledgeBaseChat(
            @Parameter(description = "知识库ID") @RequestParam Long knowledgeBaseId,
            @Parameter(description = "用户问题") @RequestParam String query) {
        
        return ragService.knowledgeBaseChat(knowledgeBaseId, query);
    }
}

