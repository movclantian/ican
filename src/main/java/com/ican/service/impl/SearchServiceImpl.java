package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentMapper;
import com.ican.model.entity.DocumentDO;
import com.ican.model.vo.DocumentVO;
import com.ican.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    
    @Override
    public List<DocumentVO> searchDocuments(String keyword, String type, Long userId) {
        log.info("全文搜索: keyword={}, type={}, userId={}", keyword, type, userId);
        
        LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentDO::getUserId, userId);
        
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
                    // 使用相似度作为分数
                    Double score = 1.0; // 默认分数
                    vectorScores.put(docId, score);
                }
            }
            
            // 2. 全文检索 - 基于关键词匹配
            LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentDO::getUserId, userId);
            
            if (StrUtil.isNotBlank(query)) {
                wrapper.like(DocumentDO::getTitle, query);
            }
            
            wrapper.orderByDesc(DocumentDO::getCreateTime);
            List<DocumentDO> textResults = documentMapper.selectList(wrapper);
            
            // 提取全文检索的文档ID
            Map<Long, Double> textScores = new HashMap<>();
            for (DocumentDO doc : textResults) {
                // 简单的TF分数：标题完全匹配得分更高
                double score = doc.getTitle().equalsIgnoreCase(query) ? 2.0 : 1.0;
                textScores.put(doc.getId(), score);
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
                query, vectorResults.size(), textResults.size(), results.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("混合搜索失败: query={}, userId={}", query, userId, e);
            // 降级到全文搜索
            log.warn("降级到全文搜索");
            return searchDocuments(query, null, userId);
        }
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

