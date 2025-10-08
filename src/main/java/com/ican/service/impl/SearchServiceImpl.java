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
import com.ican.config.MetadataKeys;

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
     * 统一搜索 API (SR-01/SR-02)
     */
    @Override
    public List<com.ican.model.vo.SearchResultVO> unifiedSearch(String query, String type, Integer topK, Long userId) {
        log.info("统一搜索: query={}, type={}, topK={}, userId={}", query, type, topK, userId);
        
        if (topK == null || topK <= 0) {
            topK = 10;
        }
        
        // 构建过滤表达式
        org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression;
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        if (userId != null) {
            if (type != null && !type.isBlank()) {
                filterExpression = builder.and(
                        builder.eq("userId", userId),
                        builder.eq("type", type)
                ).build();
            } else {
                filterExpression = builder.eq("userId", userId).build();
            }
        } else {
            // 没有用户ID则只按类型（理论上不应发生）
            filterExpression = (type != null && !type.isBlank()) ? builder.eq("type", type).build() : null;
        }
        
        // 向量检索
        SearchRequest searchRequest = SearchRequest.builder()
            .query(query)
            .topK(topK * 3) // 多检索一些用于后续处理
            .similarityThreshold(0.4)
            .filterExpression(filterExpression)
            .build();
        
        List<Document> vectorResults = vectorStore.similaritySearch(searchRequest);
        
        // 按文档分组
        Map<Long, List<Document>> documentChunks = new HashMap<>();
        for (Document doc : vectorResults) {
            Object docIdObj = doc.getMetadata().get(MetadataKeys.DOCUMENT_ID);
            if (docIdObj == null) continue;
            
            Long docId = Long.parseLong(docIdObj.toString());
            documentChunks.computeIfAbsent(docId, k -> new ArrayList<>()).add(doc);
        }
        
        // 构建搜索结果
        List<com.ican.model.vo.SearchResultVO> results = new ArrayList<>();
        for (Map.Entry<Long, List<Document>> entry : documentChunks.entrySet()) {
            Long docId = entry.getKey();
            List<Document> chunks = entry.getValue();
            
            DocumentDO document = documentMapper.selectById(docId);
            if (document == null) {
                continue;
            }
            if (document.getIsDeleted() != null && document.getIsDeleted() == 1) {
                continue; // 跳过逻辑删除
            }
            
            // 计算文档总分（取最高分）
            double maxScore = chunks.stream()
                .mapToDouble(d -> extractScore(d))
                .max().orElse(0.0);
            
            // 生成高亮片段 (SR-02)
            List<com.ican.model.vo.SearchResultVO.HighlightSnippet> snippets = chunks.stream()
                .limit(3) // 每个文档最多3个片段
                .map(doc -> {
                    String content = safeEscape(doc.getText());
                    String highlighted = highlightKeywords(content, query); // highlight 内不再整体重复转义
                    double snippetScore = extractScore(doc);
                    
                    Object chunkIndex = doc.getMetadata().get(MetadataKeys.CHUNK_INDEX);
                    int position = chunkIndex != null ? ((Number) chunkIndex).intValue() : 0;
                    
                    return com.ican.model.vo.SearchResultVO.HighlightSnippet.builder()
                        .content(highlighted)
                        .position(position)
                        .score(snippetScore)
                        .build();
                })
                .collect(Collectors.toList());
            
            com.ican.model.vo.SearchResultVO result = com.ican.model.vo.SearchResultVO.builder()
                .documentId(docId)
                .title(document.getTitle())
                .type(document.getType())
                .score(maxScore)
                .snippets(snippets)
                .uploadTime(document.getCreateTime())
                .fileSize(document.getFileSize())
                .build();
            
            results.add(result);
        }
        
        // 按分数降序排序并限制数量
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        return results.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    /**
     * 查询纠错 (SR-03)
     */
    @Override
    public String correctQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        
        log.info("查询纠错: query={}", query);
        
        // 常见拼写错误映射（可扩展）
        Map<String, String> corrections = new HashMap<>();
        corrections.put("machien learning", "machine learning");
        corrections.put("deep leraning", "deep learning");
        corrections.put("artifical intelligence", "artificial intelligence");
        corrections.put("nueral network", "neural network");
        corrections.put("trasformer", "transformer");
        corrections.put("atention", "attention");
        
        String corrected = query.toLowerCase();
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            if (corrected.contains(entry.getKey())) {
                corrected = corrected.replace(entry.getKey(), entry.getValue());
                log.info("纠错: {} -> {}", query, corrected);
            }
        }
        
        return corrected;
    }
    
    /**
     * 高亮关键词 (SR-02)
     */
    private String highlightKeywords(String text, String query) {
        if (text == null || query == null) {
            return text;
        }
        
        // 分词（简单按空格分）
        String[] keywords = query.split("\\s+");
        
        String result = text;
        for (String keyword : keywords) {
            if (keyword.isEmpty()) {
                continue;
            }
            
            // 使用 <em> 标签高亮（不区分大小写）
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(keyword), 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pattern.matcher(result);
            result = matcher.replaceAll(matchResult -> 
                "<em>" + matchResult.group() + "</em>");
        }
        
        // 截断过长的文本，保留关键词上下文
        if (result.length() > 300) {
            int highlightPos = result.indexOf("<em>");
            if (highlightPos != -1) {
                int start = Math.max(0, highlightPos - 100);
                int end = Math.min(result.length(), highlightPos + 200);
                result = (start > 0 ? "..." : "") + 
                         result.substring(start, end) + 
                         (end < result.length() ? "..." : "");
            } else {
                result = result.substring(0, 300) + "...";
            }
        }
        
        return result;
    }

    /**
     * 提取分数：优先 similarity，其次 score，最后 0
     */
    private double extractScore(Document doc) {
        Object sim = doc.getMetadata().get(MetadataKeys.SIMILARITY);
        if (sim instanceof Number n) return n.doubleValue();
        Object score = doc.getMetadata().get(MetadataKeys.SCORE);
        if (score instanceof Number n2) return n2.doubleValue();
        try {
            if (sim != null) return Double.parseDouble(sim.toString());
            if (score != null) return Double.parseDouble(score.toString());
        } catch (Exception ignored) {}
        return 0.0;
    }

    /**
     * HTML 基础转义
     */
    private String safeEscape(String text) {
        if (text == null) return null;
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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

