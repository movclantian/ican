package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ican.model.entity.DocumentES;
import com.ican.model.vo.DocumentSearchResultVO;
import com.ican.repository.DocumentESRepository;
import com.ican.service.DocumentESService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Elasticsearch 文档服务实现类
 * 使用原生 Elasticsearch Java API
 * 
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentESServiceImpl implements DocumentESService {
    
    private final DocumentESRepository documentESRepository;
    
    private final ElasticsearchClient elasticsearchClient;
    
    @Override
    public void indexDocument(Long documentId, Long userId, String title, String content,
                             String type, Long fileSize, String status) {
        try {
            DocumentES documentES = DocumentES.builder()
                .id(documentId)
                .userId(userId)
                .title(title)
                .content(content)
                .type(type)
                .fileSize(fileSize)
                .status(status)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
            
            documentESRepository.save(documentES);
            
            log.info("文档已索引到ES: documentId={}, title={}", documentId, title);
        } catch (Exception e) {
            log.error("索引文档到ES失败: documentId={}", documentId, e);
            throw new BusinessException("索引文档失败: " + e.getMessage());
        }
    }
    
    @Override
    public void updateDocumentStatus(Long documentId, String status) {
        try {
            documentESRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(status);
                doc.setUpdateTime(LocalDateTime.now());
                documentESRepository.save(doc);
                log.info("更新ES文档状态: documentId={}, status={}", documentId, status);
            });
        } catch (Exception e) {
            log.error("更新ES文档状态失败: documentId={}", documentId, e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 🆕 全文搜索文档 - 增强版(带高亮)
     * 
     * <p>功能特性:</p>
     * <ul>
     *   <li>使用 Elasticsearch BM25 算法评分</li>
     *   <li>在 title 和 content 字段中搜索</li>
     *   <li>提取高亮片段</li>
     *   <li>返回关键词列表供前端高亮</li>
     * </ul>
     * 
     * @param userId 用户ID(安全过滤)
     * @param query 搜索查询
     * @param topK 返回数量
     * @return 搜索结果列表
     */
    @Override
    public List<DocumentSearchResultVO> fullTextSearchWithHighlight(Long userId, String query, int topK) {
        if (StrUtil.isBlank(query)) {
            return new ArrayList<>();
        }
        
        try {
            // 1. 使用原生 Elasticsearch Java API 构建查询
            SearchResponse<DocumentES> response = elasticsearchClient.search(s -> s
                .index("ican_documents")
                .query(q -> q
                    .bool(b -> b
                        .must(m -> m.term(t -> t.field("userId").value(userId)))
                        .must(m -> m.multiMatch(mm -> mm
                            .query(query)
                            .fields("title", "content")
                        ))
                    )
                )
                .highlight(h -> h
                    .fields("title", f -> f
                        .fragmentSize(150)
                        .numberOfFragments(1)
                    )
                    .fields("content", f -> f
                        .fragmentSize(300)
                        .numberOfFragments(1)
                    )
                )
                .size(topK),
                DocumentES.class
            );
            
            // 2. 提取关键词(供前端高亮使用)
            List<String> keywords = extractKeywords(query);
            
            // 3. 转换结果
            List<DocumentSearchResultVO> results = new ArrayList<>();
            for (Hit<DocumentES> hit : response.hits().hits()) {
                DocumentES doc = hit.source();
                if (doc == null) continue;
                
                // 设置文档ID
                if (hit.id() != null) {
                    doc.setId(Long.parseLong(hit.id()));
                }
                
                // 提取高亮片段
                String snippet;
                if (hit.highlight() != null && !hit.highlight().isEmpty()) {
                    // 优先使用标题高亮,其次内容高亮
                    List<String> titleHighlights = hit.highlight().get("title");
                    List<String> contentHighlights = hit.highlight().get("content");
                    
                    if (titleHighlights != null && !titleHighlights.isEmpty()) {
                        snippet = String.join(" ... ", titleHighlights);
                    } else if (contentHighlights != null && !contentHighlights.isEmpty()) {
                        snippet = String.join(" ... ", contentHighlights);
                    } else {
                        snippet = truncateContent(doc.getContent(), 200);
                    }
                } else {
                    snippet = truncateContent(doc.getContent(), 200);
                }
                
                // 安全处理 score
                Double hitScore = hit.score();
                double scoreValue = (hitScore != null) ? hitScore.doubleValue() : 0.0;
                
                DocumentSearchResultVO result = DocumentSearchResultVO.builder()
                    .documentId(doc.getId())
                    .title(doc.getTitle())
                    .type(doc.getType())
                    .fileSize(doc.getFileSize())
                    .snippet(snippet)
                    .keywords(keywords)
                    .score(scoreValue)
                    .source("fulltext")
                    .build();
                
                results.add(result);
            }
            
            log.info("ES全文搜索完成: userId={}, query={}, topK={}, results={}", 
                userId, query, topK, results.size());
            
            return results;
            
        } catch (IOException e) {
            log.error("ES全文搜索失败: userId={}, query={}", userId, query, e);
            return new ArrayList<>();  // 降级处理
        }
    }
    
    /**
     * 截断内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
    
    /**
     * 从查询中提取关键词
     * 简化版: 按空格分词
     * 生产环境建议使用 IK 分词器
     */
    private List<String> extractKeywords(String query) {
        return Arrays.stream(query.trim().split("\\s+"))
            .filter(StrUtil::isNotBlank)
            .distinct()
            .collect(Collectors.toList());
    }
    
    @Override
    public void deleteDocument(Long documentId) {
        try {
            documentESRepository.deleteById(documentId);
            log.info("从ES删除文档: documentId={}", documentId);
        } catch (Exception e) {
            log.error("从ES删除文档失败: documentId={}", documentId, e);
            // 不抛出异常
        }
    }
    
    @Override
    public List<DocumentES> getUserDocuments(Long userId) {
        try {
            return documentESRepository.findByUserId(userId);
        } catch (Exception e) {
            log.error("查询用户ES文档失败: userId={}", userId, e);
            return new ArrayList<>();
        }
    }
}
