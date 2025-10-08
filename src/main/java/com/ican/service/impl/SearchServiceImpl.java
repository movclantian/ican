package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentMapper;
import com.ican.model.entity.DocumentDO;
import com.ican.model.entity.DocumentES;
import com.ican.model.vo.DocumentVO;
import com.ican.service.DocumentESService;
import com.ican.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.continew.starter.core.exception.BusinessException;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 搜索服务实现
 * 结合向量检索和全文检索的混合搜索
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    
    private final DocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final DocumentESService documentESService;
    
    @Override
    public List<DocumentVO> searchDocuments(String keyword, String type, Long userId) {
        log.info("全文搜索: keyword={}, type={}, userId={}", keyword, type, userId);
        
        // 验证用户ID
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        
        LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentDO::getUserId, userId)
               .eq(DocumentDO::getIsDeleted, 0); // 只查询未删除的文档
        
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(DocumentDO::getType, type);
        }
        
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(DocumentDO::getTitle, keyword);
        }
        
        wrapper.orderByDesc(DocumentDO::getCreateTime);
        
        List<DocumentDO> documents = documentMapper.selectList(wrapper);
        
        return documents.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<DocumentVO> hybridSearch(String query, Long userId) {
        log.info("混合搜索: query={}, userId={}", query, userId);
        
        // 验证参数
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        if (StrUtil.isBlank(query)) {
            return new ArrayList<>();
        }
        
        try {
            // 1. 向量检索 - 基于语义相似度
            SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragConfig.getHybridSearch().getVectorTopK())
                .similarityThreshold(ragConfig.getHybridSearch().getVectorSimilarityThreshold())
                .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build())
                .build();
            
            List<Document> vectorResults = vectorStore.similaritySearch(searchRequest);
            
            // 提取文档ID和相似度分数
            Map<Long, Double> vectorScores = new HashMap<>();
            for (Document doc : vectorResults) {
                Object docIdObj = doc.getMetadata().get("documentId");
                if (docIdObj != null) {
                    Long docId = Long.valueOf(docIdObj.toString());
                    // 使用相似度分数，如果没有则使用默认值
                    Double score = doc.getMetadata().get("similarity") != null ? 
                        Double.valueOf(doc.getMetadata().get("similarity").toString()) : 0.8;
                    vectorScores.put(docId, score);
                }
            }
            
            // 2. 全文检索 - 使用Elasticsearch进行高级文本搜索
            List<DocumentES> esResults = documentESService.searchDocuments(userId, query);
            
            // 提取全文检索的文档ID和分数
            Map<Long, Double> textScores = new HashMap<>();
            
            if (!esResults.isEmpty()) {
                // 使用ES搜索结果
                log.info("使用ES搜索结果: count={}", esResults.size());
                for (int i = 0; i < esResults.size(); i++) {
                    DocumentES doc = esResults.get(i);
                    // 基于排名计算分数: 排名越前分数越高
                    double score = 1.0 - (i * 0.1);  // 第一个1.0, 第二个0.9, 以此类推
                    if (score < 0.1) score = 0.1;  // 最低分0.1
                    
                    // 如果标题和内容都匹配，提升分数
                    if (doc.getTitle().contains(query) && doc.getContent().contains(query)) {
                        score *= 1.2;
                    }
                    
                    textScores.put(doc.getId(), score);
                }
            } else {
                // ES降级处理：使用数据库查询
                log.warn("ES搜索返回空结果或失败，降级使用数据库查询");
                LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DocumentDO::getUserId, userId)
                       .eq(DocumentDO::getIsDeleted, 0);
                
                if (StrUtil.isNotBlank(query)) {
                    wrapper.like(DocumentDO::getTitle, query);
                }
                
                wrapper.orderByDesc(DocumentDO::getCreateTime);
                List<DocumentDO> textResults = documentMapper.selectList(wrapper);
                
                for (DocumentDO doc : textResults) {
                    double score = calculateTextScore(doc.getTitle(), query);
                    textScores.put(doc.getId(), score);
                }
            }
            
            // 3. 融合排序 (Reciprocal Rank Fusion - RRF)
            // 合并两个结果集
            Set<Long> allDocIds = new HashSet<>();
            allDocIds.addAll(vectorScores.keySet());
            allDocIds.addAll(textScores.keySet());
            
            // 计算融合分数
            Map<Long, Double> fusionScores = new HashMap<>();
            for (Long docId : allDocIds) {
                double vectorScore = vectorScores.getOrDefault(docId, 0.0);
                double textScore = textScores.getOrDefault(docId, 0.0);
                
                // 使用配置的权重进行加权融合
                double fusionScore = vectorScore * ragConfig.getHybridSearch().getVectorWeight() 
                                   + textScore * ragConfig.getHybridSearch().getTextWeight();
                fusionScores.put(docId, fusionScore);
            }
            
            // 4. 按融合分数排序并返回
            List<Long> sortedDocIds = fusionScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
            
            // 5. 查询文档详情并返回
            List<DocumentVO> results = new ArrayList<>();
            for (Long docId : sortedDocIds) {
                DocumentDO doc = documentMapper.selectById(docId);
                if (doc != null) {
                    results.add(convertToVO(doc));
                }
            }
            
            log.info("混合搜索完成: query={}, vectorResults={}, textResults={}, finalResults={}", 
                query, vectorResults.size(), textScores.size(), results.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("混合搜索失败: query={}, userId={}", query, userId, e);
            // 降级到全文搜索
            log.warn("降级到全文搜索");
            return searchDocuments(query, null, userId);
        }
    }
    
    /**
     * 计算文本匹配分数
     */
    private double calculateTextScore(String title, String query) {
        if (StrUtil.isBlank(title) || StrUtil.isBlank(query)) {
            return 0.0;
        }
        
        String lowerTitle = title.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        // 完全匹配得分最高
        if (lowerTitle.equals(lowerQuery)) {
            return 3.0;
        }
        
        // 包含查询词
        if (lowerTitle.contains(lowerQuery)) {
            // 计算匹配度
            int matchCount = 0;
            String[] queryWords = lowerQuery.split("\\s+");
            for (String word : queryWords) {
                if (lowerTitle.contains(word)) {
                    matchCount++;
                }
            }
            return 1.0 + (double) matchCount / queryWords.length;
        }
        
        // 部分匹配
        return 0.5;
    }
    
    /**
     * 转换为 VO
     */
    private DocumentVO convertToVO(DocumentDO document) {
        return DocumentVO.builder()
            .id(document.getId())
            .title(document.getTitle())
            .type(document.getType())
            .fileSize(document.getFileSize())
            .status(document.getStatus())
            .createTime(document.getCreateTime())
            .updateTime(document.getUpdateTime())
            .build();
    }
}

