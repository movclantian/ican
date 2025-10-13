package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ican.model.dto.DocumentQueryDTO;
import com.ican.model.vo.DocumentFileVO;
import com.ican.model.vo.DocumentVO;
import com.ican.service.DocumentService;
import com.ican.service.DocumentTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
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
    private final DocumentTaskService documentTaskService;
    
    /**
     * 上传文档（支持单个或批量）
     * 
     * @param files 文件列表（单个或多个）
     * @param type 文档类型 (research_paper, teaching_material, other)
     * @return 上传结果
     */
    @Operation(summary = "上传文档", description = "支持 PDF、Word、Markdown、TXT 格式；支持单个或批量上传")
    @PostMapping("/upload")
    public Map<String, Object> uploadDocument(
            @Parameter(description = "文件（单个或多个）") @RequestParam("file") MultipartFile[] files,
            @Parameter(description = "文档类型") @RequestParam(value = "type", defaultValue = "other") String type) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 单个文件上传
        if (files.length == 1) {
            Long documentId = documentService.uploadDocument(files[0], type, userId);
            return Map.of(
                "documentId", documentId,
                "message", "文档上传成功,正在处理中...",
                "tasksUrl", "/api/documents/" + documentId + "/tasks"
            );
        }
        
        // 批量上传
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        
        for (MultipartFile file : files) {
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("filename", file.getOriginalFilename());
            
            try {
                Long documentId = documentService.uploadDocument(file, type, userId);
                result.put("success", true);
                result.put("documentId", documentId);
                result.put("message", "上传成功");
                result.put("tasksUrl", "/api/documents/" + documentId + "/tasks");
                successCount++;
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "上传失败: " + e.getMessage());
                failureCount++;
            }
            
            results.add(result);
        }
        
        return Map.of(
            "total", files.length,
            "success", successCount,
            "failure", failureCount,
            "results", results,
            "message", String.format("批量上传完成: 成功 %d 个，失败 %d 个", successCount, failureCount)
        );
    }
    
    /**
     * 分页查询用户的文档列表
     * 
     * @param queryDTO 查询参数
     * @return 分页结果
     */
    @Operation(summary = "分页查询文档列表", description = "支持按标题、类型、状态、知识库等条件查询，支持分页和排序")
    @GetMapping("/list")
    public IPage<DocumentVO> getUserDocuments(DocumentQueryDTO queryDTO) {
        Long userId = StpUtil.getLoginIdAsLong();
        return documentService.pageUserDocuments(userId, queryDTO);
    }
    
    /**
     * 获取文档详情
     * 
     * @param documentId 文档ID
     * @return 文档信息
     */
    @Operation(summary = "获取文档详情", description = "获取指定文档的详细信息（需拥有者权限且未被删除）")
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
    @Operation(summary = "检索相关文档", description = "基于向量相似度检索最相关的文档（仅限自己文档向量空间）")
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
    @Operation(summary = "删除文档", description = "逻辑删除文档并清除其向量及映射数据")
    @DeleteMapping("/{documentId}")
    public Map<String, String> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable Long documentId) {
        
        documentService.deleteDocument(documentId);
        
        return Map.of("message", "文档删除成功");
    }
    
    /**
     * 获取文档任务状态 (DP-01)
     * 
     * @param documentId 文档ID
     * @return 任务列表
     */
    @Operation(summary = "获取文档任务状态", description = "查看文档处理任务的状态和进度（按创建时间倒序）")
    @GetMapping("/{documentId}/tasks")
    public List<com.ican.model.vo.DocumentTaskVO> getDocumentTasks(
            @Parameter(description = "文档ID") @PathVariable Long documentId) {
        return documentTaskService.getDocumentTasks(documentId);
    }
    
    /**
     * 重试失败的任务 (DP-02)
     * 
     * @param taskId 任务ID
     * @return 操作结果
     */
    @Operation(summary = "重试文档处理", description = "重新处理失败的文档（自动清理旧数据并重新解析、向量化）")
    @PostMapping("/tasks/{taskId}/retry")
    public Map<String, Object> retryTask(
            @Parameter(description = "任务ID") @PathVariable Long taskId) {
        boolean success = documentTaskService.retryTask(taskId);
        return Map.of(
            "message", "文档处理已重新开始",
            "taskId", taskId,
            "success", success
        );
    }

    
    /**
     * 获取文档内容
     *
     * @param documentId 文档ID
     * @return 文档内容
     */
    @Operation(summary = "获取文档内容", description = "获取文档解析后的纯文本内容")
    @GetMapping("/{documentId}/content")
    public String getDocumentContent(
            @Parameter(description = "文档ID") @PathVariable Long documentId) {

        return documentService.parseDocument(documentId);
    }

    /**
     * 文档预览
     *
     * @param documentId 文档ID
     * @param download   是否下载
     * @return 文档文件流
     */
    @Operation(summary = "文档预览", description = "获取文档原始文件以支持在线预览或下载")
    @GetMapping("/{documentId}/preview")
    public ResponseEntity<ByteArrayResource> previewDocument(
            @Parameter(description = "文档ID") @PathVariable Long documentId,
            @Parameter(description = "是否下载") @RequestParam(value = "download", defaultValue = "false") boolean download) {

        DocumentFileVO documentFile = documentService.getDocumentFile(documentId);
        String filename = documentFile.getFilename();
        String encodedFilename = UriUtils.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        String dispositionType = download ? "attachment" : "inline";
        ByteArrayResource resource = new ByteArrayResource(documentFile.getData());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(documentFile.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, dispositionType + "; filename=\"" + encodedFilename + "\"")
                .contentLength(documentFile.getData().length)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .body(resource);
    }
}

