package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.ican.model.vo.DocumentVO;
import com.ican.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Tag(name = "文档管理", description = "文档上传、解析、检索等功能")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@SaCheckLogin
public class DocumentController {
    
    private final DocumentService documentService;
    
    /**
     * 上传文档
     * 
     * @param file 文件
     * @param type 文档类型 (research_paper, teaching_material, other)
     * @return 文档ID
     */
    @Operation(summary = "上传文档", description = "支持 PDF、Word、Markdown、TXT 格式")
    @PostMapping("/upload")
    public Map<String, Object> uploadDocument(
            @Parameter(description = "文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档类型") @RequestParam(value = "type", defaultValue = "other") String type) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        Long documentId = documentService.uploadDocument(file, type, userId);
        
        return Map.of(
            "documentId", documentId,
            "message", "文档上传成功,正在处理中..."
        );
    }
    
    /**
     * 获取用户的文档列表
     * 
     * @param type 文档类型(可选)
     * @return 文档列表
     */
    @Operation(summary = "获取文档列表", description = "获取当前用户上传的所有文档")
    @GetMapping("/list")
    public List<DocumentVO> getUserDocuments(
            @Parameter(description = "文档类型") @RequestParam(required = false) String type) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        return documentService.getUserDocuments(userId, type);
    }
    
    /**
     * 获取文档详情
     * 
     * @param documentId 文档ID
     * @return 文档信息
     */
    @Operation(summary = "获取文档详情", description = "获取指定文档的详细信息")
    @GetMapping("/{documentId}")
    public DocumentVO getDocument(
            @Parameter(description = "文档ID") @PathVariable Long documentId) {
        
        return documentService.getDocument(documentId);
    }
    
    /**
     * 检索相关文档
     * 
     * @param query 查询文本
     * @param topK 返回数量
     * @return 相关文档列表
     */
    @Operation(summary = "检索相关文档", description = "基于向量相似度检索最相关的文档")
    @GetMapping("/search")
    public Map<String, Object> searchDocuments(
            @Parameter(description = "查询文本") @RequestParam("query") String query,
            @Parameter(description = "返回数量") @RequestParam(value = "topK", defaultValue = "5") int topK) {
        
        List<Document> documents = documentService.searchSimilarDocuments(query, topK);
        
        return Map.of(
            "query", query,
            "results", documents.stream().map(doc -> {
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("content", doc.getText());
                result.put("metadata", doc.getMetadata());
                return result;
            }).toList()
        );
    }
    
    /**
     * 删除文档
     * 
     * @param documentId 文档ID
     * @return 操作结果
     */
    @Operation(summary = "删除文档", description = "删除文档及其向量数据")
    @DeleteMapping("/{documentId}")
    public Map<String, String> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable Long documentId) {
        
        documentService.deleteDocument(documentId);
        
        return Map.of("message", "文档删除成功");
    }
}

