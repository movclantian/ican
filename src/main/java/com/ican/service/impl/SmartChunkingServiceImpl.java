package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import com.ican.model.vo.DocumentMetadataVO;
import com.ican.service.SmartChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 智能文档分块服务实现
 * 
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartChunkingServiceImpl implements SmartChunkingService {
    
    private final EmbeddingModel embeddingModel;
    
    // 语义分块相似度阈值(低于此值则分块)
    private static final double SEMANTIC_THRESHOLD = 0.5;
    
    // 句子分隔符
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
        "[。！?;\\n]+|[.!?;\\n]+\\s+",
        Pattern.MULTILINE
    );
    
    @Override
    public List<ChunkResult> smartChunk(String content, DocumentMetadataVO metadata, int chunkSize, int overlapSize) {
        // 决策: 有章节信息 → 章节分块, 否则 → 语义分块
        if (metadata != null && metadata.getSections() != null && !metadata.getSections().isEmpty()) {
            log.info("检测到章节结构,使用章节分块策略: sections={}", metadata.getSections().size());
            return chunkBySections(metadata, overlapSize);
        } else {
            log.info("未检测到章节结构,使用语义分块策略");
            return semanticChunk(content, chunkSize, overlapSize);
        }
    }
    
    @Override
    public List<ChunkResult> chunkBySections(DocumentMetadataVO metadata, int overlapSize) {
        if (metadata == null || metadata.getSections() == null || metadata.getSections().isEmpty()) {
            log.warn("章节信息为空,无法进行章节分块");
            return new ArrayList<>();
        }
        
        List<ChunkResult> results = new ArrayList<>();
        List<DocumentMetadataVO.Section> sections = metadata.getSections();
        
        for (int i = 0; i < sections.size(); i++) {
            DocumentMetadataVO.Section section = sections.get(i);
            
            // 构建分块内容: 标题 + 内容
            StringBuilder chunkContent = new StringBuilder();
            
            // 添加标题(带层级标记)
            String titlePrefix = "#".repeat(section.getLevel()) + " ";
            chunkContent.append(titlePrefix).append(section.getTitle()).append("\n\n");
            chunkContent.append(section.getContent());
            
            // 添加 overlap: 包含下一章节的开头部分
            if (i < sections.size() - 1 && overlapSize > 0) {
                DocumentMetadataVO.Section nextSection = sections.get(i + 1);
                String nextContent = nextSection.getContent();
                
                // 计算 overlap 字符数(简化: overlapSize * 4)
                int overlapChars = overlapSize * 4;
                if (nextContent.length() > overlapChars) {
                    chunkContent.append("\n\n--- 下一章节预览 ---\n");
                    chunkContent.append(nextContent.substring(0, overlapChars)).append("...");
                }
            }
            
            ChunkResult chunk = new ChunkResult(
                chunkContent.toString(),
                "section",
                section.getStartPosition(),
                section.getEndPosition()
            );
            chunk.setSectionTitle(section.getTitle());
            chunk.setSectionLevel(section.getLevel());
            
            results.add(chunk);
        }
        
        log.info("章节分块完成: sections={}, chunks={}", sections.size(), results.size());
        return results;
    }
    
    @Override
    public List<ChunkResult> semanticChunk(String content, int chunkSize, int overlapSize) {
        if (StrUtil.isBlank(content)) {
            return new ArrayList<>();
        }
        
        try {
            // 1. 分割为句子
            List<String> sentences = splitIntoSentences(content);
            log.info("句子分割完成: sentences={}", sentences.size());
            
            if (sentences.size() <= 1) {
                // 只有一句话,直接返回
                ChunkResult chunk = new ChunkResult(content, "semantic", 0, content.length());
                return List.of(chunk);
            }
            
            // 2. 计算句子间的语义相似度
            List<Double> similarities = computeSentenceSimilarities(sentences);
            
            // 3. 在相似度低的地方分块
            List<Integer> breakpoints = findBreakpoints(similarities, SEMANTIC_THRESHOLD);
            log.info("语义断裂点: count={}, positions={}", breakpoints.size(), breakpoints);
            
            // 4. 根据断裂点生成分块
            List<ChunkResult> results = new ArrayList<>();
            int currentPosition = 0;
            int startIdx = 0;
            
            for (int breakIdx : breakpoints) {
                // 合并 [startIdx, breakIdx] 的句子
                String chunkContent = mergeSentences(sentences, startIdx, breakIdx + 1);
                
                ChunkResult chunk = new ChunkResult(
                    chunkContent,
                    "semantic",
                    currentPosition,
                    currentPosition + chunkContent.length()
                );
                results.add(chunk);
                
                currentPosition += chunkContent.length();
                startIdx = breakIdx + 1;
            }
            
            // 添加最后一个分块
            if (startIdx < sentences.size()) {
                String chunkContent = mergeSentences(sentences, startIdx, sentences.size());
                ChunkResult chunk = new ChunkResult(
                    chunkContent,
                    "semantic",
                    currentPosition,
                    currentPosition + chunkContent.length()
                );
                results.add(chunk);
            }
            
            // 5. 添加 overlap
            if (overlapSize > 0) {
                results = addOverlap(results, overlapSize);
            }
            
            log.info("语义分块完成: sentences={}, chunks={}", sentences.size(), results.size());
            return results;
            
        } catch (Exception e) {
            log.error("语义分块失败,降级为简单分块", e);
            return fallbackChunk(content, chunkSize, overlapSize);
        }
    }
    
    /**
     * 分割为句子
     */
    private List<String> splitIntoSentences(String content) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(content);
        
        int lastEnd = 0;
        while (matcher.find()) {
            String sentence = content.substring(lastEnd, matcher.end()).trim();
            if (StrUtil.isNotBlank(sentence)) {
                sentences.add(sentence);
            }
            lastEnd = matcher.end();
        }
        
        // 添加最后一句
        if (lastEnd < content.length()) {
            String lastSentence = content.substring(lastEnd).trim();
            if (StrUtil.isNotBlank(lastSentence)) {
                sentences.add(lastSentence);
            }
        }
        
        return sentences;
    }
    
    /**
     * 计算句子间的语义相似度
     * 
     * 优化: 批量嵌入,减少API调用
     */
    private List<Double> computeSentenceSimilarities(List<String> sentences) {
        List<Double> similarities = new ArrayList<>();
        
        // 批量获取句子的向量表示(最多10个一批)
        int batchSize = 10;
        List<List<Double>> embeddings = new ArrayList<>();
        
        for (int i = 0; i < sentences.size(); i += batchSize) {
            int end = Math.min(i + batchSize, sentences.size());
            List<String> batch = sentences.subList(i, end);
            
            try {
                EmbeddingResponse response = embeddingModel.embedForResponse(batch);
                for (int j = 0; j < response.getResults().size(); j++) {
                    float[] floatArray = response.getResults().get(j).getOutput();
                    List<Double> doubleList = new ArrayList<>();
                    for (float f : floatArray) {
                        doubleList.add((double) f);
                    }
                    embeddings.add(doubleList);
                }
            } catch (Exception e) {
                log.warn("批量嵌入失败,跳过该批次: batch=[{}, {})", i, end, e);
                // 填充默认向量
                for (int j = 0; j < batch.size(); j++) {
                    embeddings.add(new ArrayList<>());
                }
            }
        }
        
        // 计算相邻句子的余弦相似度
        for (int i = 0; i < embeddings.size() - 1; i++) {
            List<Double> vec1 = embeddings.get(i);
            List<Double> vec2 = embeddings.get(i + 1);
            
            if (vec1.isEmpty() || vec2.isEmpty()) {
                similarities.add(0.0);  // 默认不相似
            } else {
                double similarity = cosineSimilarity(vec1, vec2);
                similarities.add(similarity);
            }
        }
        
        return similarities;
    }
    
    /**
     * 余弦相似度计算
     */
    private double cosineSimilarity(List<Double> vec1, List<Double> vec2) {
        if (vec1.size() != vec2.size()) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.size(); i++) {
            dotProduct += vec1.get(i) * vec2.get(i);
            norm1 += vec1.get(i) * vec1.get(i);
            norm2 += vec2.get(i) * vec2.get(i);
        }
        
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    /**
     * 查找语义断裂点
     */
    private List<Integer> findBreakpoints(List<Double> similarities, double threshold) {
        List<Integer> breakpoints = new ArrayList<>();
        
        for (int i = 0; i < similarities.size(); i++) {
            if (similarities.get(i) < threshold) {
                breakpoints.add(i);
            }
        }
        
        return breakpoints;
    }
    
    /**
     * 合并句子
     */
    private String mergeSentences(List<String> sentences, int start, int end) {
        return sentences.subList(start, end).stream()
            .collect(Collectors.joining(" "));
    }
    
    /**
     * 添加 overlap
     */
    private List<ChunkResult> addOverlap(List<ChunkResult> chunks, int overlapSize) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        
        List<ChunkResult> withOverlap = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            ChunkResult chunk = chunks.get(i);
            StringBuilder newContent = new StringBuilder(chunk.getContent());
            
            // 添加下一个块的开头作为 overlap
            if (i < chunks.size() - 1) {
                ChunkResult nextChunk = chunks.get(i + 1);
                String nextContent = nextChunk.getContent();
                
                int overlapChars = overlapSize * 4;  // token -> chars
                if (nextContent.length() > overlapChars) {
                    newContent.append("\n\n--- overlap ---\n");
                    newContent.append(nextContent.substring(0, overlapChars));
                }
            }
            
            chunk.setContent(newContent.toString());
            withOverlap.add(chunk);
        }
        
        return withOverlap;
    }
    
    /**
     * 降级方案: 简单按字符数分块
     */
    private List<ChunkResult> fallbackChunk(String content, int chunkSize, int overlapSize) {
        List<ChunkResult> results = new ArrayList<>();
        int chunkChars = chunkSize * 4;  // token -> chars
        int overlapChars = overlapSize * 4;
        
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkChars, content.length());
            String chunkContent = content.substring(start, end);
            
            ChunkResult chunk = new ChunkResult(chunkContent, "fallback", start, end);
            results.add(chunk);
            
            start += (chunkChars - overlapChars);
        }
        
        log.info("降级分块完成: chunks={}", results.size());
        return results;
    }
}
