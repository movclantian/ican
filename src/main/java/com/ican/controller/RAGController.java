package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ican.model.dto.PaperComparisonDTO;
import com.ican.model.vo.PaperComparisonVO;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.RagAnswerVO;
import com.ican.model.vo.RagChatResultVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.CitationFormatService;
import com.ican.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 功能控制器
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Tag(name = "RAG 功能", description = "检索增强生成相关功能")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@SaCheckLogin
public class RAGController {
    
    private final RAGService ragService;
    private final CitationFormatService citationFormatService;
    
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
     * 论文总结 - 结构化输出（带引用）
     */
    @Operation(summary = "论文总结", description = "生成结构化 JSON（背景/方法/结果/创新点/局限），并附引用片段；若字段缺失会填充空字符串或空数组")
    @PostMapping("/summarize-paper")
    public RagAnswerVO<PaperSummaryVO> summarizePaper(
            @Parameter(description = "论文文档ID") @RequestParam Long documentId) {
        
        return ragService.summarizePaper(documentId);
    }
    
    /**
     * 生成教学设计 - 结构化输出（带引用）
     */
    @Operation(summary = "生成教学设计", description = "结合主题/年级/学科与可选文档，生成结构化教学方案（目标/步骤/作业/资源），附引用片段")
    @PostMapping("/generate-teaching-plan")
    public RagAnswerVO<TeachingPlanVO> generateTeachingPlan(
            @Parameter(description = "课题") @RequestParam String topic,
            @Parameter(description = "学段") @RequestParam String grade,
            @Parameter(description = "学科") @RequestParam String subject,
            @Parameter(description = "参考文档ID列表") @RequestParam(required = false) List<Long> documentIds) {
        
        return ragService.generateTeachingPlan(topic, grade, subject, documentIds);
    }
    
    /**
     * 论文对比 - 多文献对比分析 (PA-01)
     */
    @Operation(summary = "论文对比", description = "多篇论文多维度对比（默认维度：方法/数据集/创新点/结论/实验结果），输出结构化矩阵 + 引用片段")
    @PostMapping("/compare-papers")
    public RagAnswerVO<PaperComparisonVO> comparePapers(
            @Validated @RequestBody PaperComparisonDTO request) {
        
        return ragService.comparePapers(request.getDocumentIds(), request.getDimensions());
    }
    
    /**
     * 抽取论文元数据 (PA-03)
     */
    @Operation(summary = "提取论文元数据", description = "从论文内容抽取题录信息（title/authors/year/doi/keywords 等）")
    @PostMapping("/extract-metadata")
    public com.ican.model.vo.PaperMetadataVO extractPaperMetadata(
            @Parameter(description = "论文文档ID") @RequestParam Long documentId) {
        
        return ragService.extractPaperMetadata(documentId);
    }
    
    /**
     * 导出引用格式 (PA-04)
     */
    @Operation(summary = "导出引用格式", description = "生成指定引用格式(BibTeX/APA/MLA/GB/T7714)；若缺字段使用空串占位")
    @GetMapping("/export-citation")
    public Map<String, String> exportCitation(
            @Parameter(description = "论文文档ID") @RequestParam Long documentId,
            @Parameter(description = "引用格式（bibtex/apa/mla/gbt7714）") @RequestParam(defaultValue = "apa") String format) {
        
        com.ican.model.vo.PaperMetadataVO metadata = ragService.extractPaperMetadata(documentId);
        
        String citation;
        switch (format.toLowerCase()) {
            case "bibtex", "bib" -> citation = citationFormatService.generateBibTeX(metadata);
            case "mla" -> citation = citationFormatService.generateMLA(metadata);
            case "gbt7714", "gb" -> citation = citationFormatService.generateGBT7714(metadata);
            default -> citation = citationFormatService.generateAPA(metadata);
        }
        
        return Map.of(
            "format", format,
            "citation", citation
        );
    }
    
    /**
     * 批量导出引用 (PA-04)
     */
    @Operation(summary = "批量导出引用", description = "批量导出多篇论文引用格式（串行处理确保数据库连接安全），返回包含文档标题和引用的列表")
    @PostMapping("/batch-export-citations")
    public Map<String, Object> batchExportCitations(
            @Parameter(description = "文档ID列表") @RequestBody List<Long> documentIds,
            @Parameter(description = "引用格式") @RequestParam(defaultValue = "apa") String format) {
        
        // 注意：由于 MyBatis Mapper 不是线程安全的，这里使用串行处理
        // 如果需要并行，需要为每个线程创建独立的 SqlSession
        List<Map<String, String>> citationList = documentIds.stream()
            .map(docId -> {
                try {
                    com.ican.model.vo.PaperMetadataVO metadata = ragService.extractPaperMetadata(docId);
                    
                    String citation;
                    switch (format.toLowerCase()) {
                        case "bibtex", "bib" -> citation = citationFormatService.generateBibTeX(metadata);
                        case "mla" -> citation = citationFormatService.generateMLA(metadata);
                        case "gbt7714", "gb" -> citation = citationFormatService.generateGBT7714(metadata);
                        default -> citation = citationFormatService.generateAPA(metadata);
                    }
                    
                    return Map.of(
                        "title", metadata.getTitle() != null ? metadata.getTitle() : "",
                        "citation", citation
                    );
                } catch (Exception e) {
                    // 记录详细错误日志便于排查
                    log.error("导出引用失败: documentId={}, format={}", docId, format, e);
                    return Map.of(
                        "title", "提取失败",
                        "citation", "",
                        "error", e.getMessage() != null ? e.getMessage() : "未知错误"
                    );
                }
            })
            .toList();
        
        return Map.of(
            "format", format,
            "count", citationList.size(),
            "citations", citationList
        );
    }
    
    /**
     * 创新点聚合 (PA-02)
     */
    @Operation(summary = "创新点聚合", description = "抽取多篇论文创新点并进行主题聚类，输出创新主题集合")
    @PostMapping("/aggregate-innovations")
    public List<com.ican.model.vo.InnovationClusterVO> aggregateInnovations(
            @Parameter(description = "论文文档ID列表") @RequestBody List<Long> documentIds) {
        
        return ragService.aggregateInnovations(documentIds);
    }
}

