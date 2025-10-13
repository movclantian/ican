package com.ican.service.impl;

import com.ican.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rerank 服务实现
 * 使用 LLM 对候选文档进行重排序
 * 
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankServiceImpl implements RerankService {
    
    // 指定使用普通聊天的 ChatClient，避免与 ragChatClient Bean 冲突
    @Qualifier("normalChatClient")
    private final ChatClient chatClient;
    
    @Override
    public List<Long> rerank(String query, Map<Long, String> candidates, int topK) {
        log.info("Rerank开始: query={}, candidateCount={}, topK={}", query, candidates.size(), topK);
        
        if (candidates.isEmpty()) {
            return List.of();
        }
        
        // 为每个候选文档计算相关性分数
        Map<Long, Double> scores = new HashMap<>();
        for (Map.Entry<Long, String> entry : candidates.entrySet()) {
            double score = computeRelevanceScore(query, entry.getValue());
            scores.put(entry.getKey(), score);
        }
        
        // 按分数降序排序并返回前K个
        List<Long> rerankedIds = scores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        log.info("Rerank完成: 返回{}个文档", rerankedIds.size());
        return rerankedIds;
    }
    
    @Override
    public double computeRelevanceScore(String query, String document) {
        try {
            // 使用简化的启发式方法计算相关性
            // 在实际生产中可以使用专门的 rerank 模型
            
            String prompt = String.format(
                "请评估以下文档与查询的相关性，给出0-10的分数（仅返回数字）：\n\n" +
                "查询：%s\n\n" +
                "文档：%s",
                query,
                document.length() > 500 ? document.substring(0, 500) + "..." : document
            );
            
            List<Message> messages = List.of(
                new SystemMessage("你是一个文档相关性评估专家。请仅返回0-10之间的数字分数。"),
                new UserMessage(prompt)
            );
            
            String response = chatClient.prompt(new Prompt(messages))
                .call()
                .content();
            
            // 解析分数
            double score = parseScore(response);
            return score / 10.0; // 归一化到 0-1
            
        } catch (Exception e) {
            log.warn("计算相关性分数失败，使用关键词匹配: {}", e.getMessage());
            return computeKeywordScore(query, document);
        }
    }
    
    /**
     * 解析LLM返回的分数
     */
    private double parseScore(String response) {
        try {
            // 提取数字
            String cleaned = response.replaceAll("[^0-9.]", "");
            double score = Double.parseDouble(cleaned);
            return Math.max(0, Math.min(10, score)); // 限制在0-10之间
        } catch (Exception e) {
            log.warn("解析分数失败: {}", response);
            return 5.0; // 默认中等分数
        }
    }
    
    /**
     * 基于关键词匹配的备用评分方法
     */
    private double computeKeywordScore(String query, String document) {
        String[] queryTokens = query.toLowerCase().split("\\s+");
        String docLower = document.toLowerCase();
        
        int matchCount = 0;
        for (String token : queryTokens) {
            if (docLower.contains(token)) {
                matchCount++;
            }
        }
        
        return (double) matchCount / queryTokens.length;
    }
}
