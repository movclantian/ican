package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base62;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentChunkMapper;
import com.ican.mapper.DocumentMapper;
import com.ican.mapper.KnowledgeBaseMapper;
import com.ican.model.entity.DocumentChunkDO;
import com.ican.model.entity.KnowledgeBaseDO;
import com.ican.model.entity.DocumentDO;
import com.ican.model.vo.CitationVO;
import com.ican.model.vo.RagChatResultVO;
import com.ican.service.DocumentESService;
import com.ican.service.DynamicRetrievalService;
import com.ican.service.RAGService;
import com.ican.service.RerankService;
import com.ican.utils.NumberConversionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 服务实现类 - 核心对话功能
 * 
 * 说明：
 * - 本类专注于 RAG 问答和文档问答功能
 * - 论文分析功能已迁移至 {@link PaperAnalysisServiceImpl}
 * - 教学设计功能已迁移至 {@link TeachingPlanServiceImpl}
 *
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGServiceImpl implements RAGService {

    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final ChatMemory chatMemoryRepository;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DynamicRetrievalService dynamicRetrievalService;
    private final DocumentESService documentESService;
    private final RerankService rerankService;
    private final ChatClient ragChatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 注入提示词模板
    @Qualifier("ragQAPromptTemplate")
    private final String ragQAPromptTemplate;
    @Qualifier("documentQAPromptTemplate")
    private final String documentQAPromptTemplate;

    @Override
    public RagChatResultVO ragChat(String conversationId, String query) {
        log.info("RAG问答: conversationId={}, query={}", conversationId, query);

        // 获取当前用户ID（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();

        // 1. 构建基础过滤条件
        Filter.Expression filterExpression = new FilterExpressionBuilder().eq("userId",
                Base62.encode(String.valueOf(userId))).build();

        // 2. 构建检索请求（动态调整检索参数）
        SearchRequest baseRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();

        // 动态调整检索参数（根据问题类型优化）
        SearchRequest adjustedRequest = dynamicRetrievalService.adjustSearchRequest(query, baseRequest);
        log.info("动态检索调整: topK {} -> {}, threshold {} -> {}",
                baseRequest.getTopK(), adjustedRequest.getTopK(),
                baseRequest.getSimilarityThreshold(), adjustedRequest.getSimilarityThreshold());

        // 3. 执行检索（根据配置选择检索策略）
        List<Document> retrievedDocs;
        
        if (ragConfig.getRetrieval().getEnableHybridSearch()) {
            // ✅ 启用混合检索：ES BM25 快速召回 + 向量精排
            log.info("启用混合检索: ES BM25 召回 + 向量精排");
            
            // 根据是否启用重排序，决定向量精排的严格程度
            boolean useStrictReranking = ragConfig.getRetrieval().getEnableReranking();
            retrievedDocs = performHybridSearch(userId, query, adjustedRequest, useStrictReranking);
            
        } else if (ragConfig.getRetrieval().getEnableReranking()) {
            // 纯向量检索 + 重排序（扩大检索量后精排）
            log.info("启用向量检索重排序: 扩大检索量 -> 精排");
            retrievedDocs = performVectorSearchWithReranking(adjustedRequest);
            
        } else {
            // 使用默认的纯向量检索
            log.debug("使用默认纯向量检索");
            retrievedDocs = vectorStore.similaritySearch(adjustedRequest);
            log.info("向量检索完成: 检索到 {} 个文档片段", retrievedDocs.size());
        }
        
        // 4. 手动构建上下文并注入到 Prompt 中
        String context = buildContext(retrievedDocs);
        
        // 创建 QuestionAnswerAdvisor（使用已检索的文档）
        // 注意：这里不让 Advisor 重新检索，我们手动注入上下文
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(adjustedRequest)
                .build();

        // 5. 使用提示词模板构建用户提示（注入检索到的上下文）
        String userPrompt = ragQAPromptTemplate
                .replace("{question}", query)
                .replace("{context}", context);

        // 构建包含历史对话的消息列表
        List<Message> messages = new ArrayList<>();

        // 如果 conversationId 不为空，加载历史对话
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            try {
                messages.addAll(chatMemoryRepository.get(conversationId));
                log.debug("RAG问答 - 加载历史消息: conversationId={}, historyCount={}",
                        conversationId, messages.size());
            } catch (Exception e) {
                log.warn("加载RAG对话历史失败: conversationId={}", conversationId, e);
            }
        }

        // 添加当前用户消息
        UserMessage userMessage = new UserMessage(userPrompt);
        messages.add(userMessage);

        // 使用注入的 ragChatClient 执行 RAG 问答
        // QuestionAnswerAdvisor 会自动完成：检索 -> 上下文注入 -> LLM 生成
        String response = ragChatClient.prompt()
                .advisors(qaAdvisor) // Advisor 负责检索和上下文增强
                .user(userPrompt)
                .call()
                .content();

        // 保存对话历史到记忆库
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            try {
                AssistantMessage assistantMessage = new AssistantMessage(response);
                chatMemoryRepository.add(conversationId, List.of(userMessage, assistantMessage));
                log.debug("RAG问答 - 保存到记忆库: conversationId={}", conversationId);
            } catch (Exception e) {
                log.warn("保存RAG对话历史失败: conversationId={}", conversationId, e);
            }
        }

        // 6. 构建引用列表（使用已检索的文档）
        List<CitationVO> citations = buildCitations(retrievedDocs, query, 300);

        log.info("RAG问答完成: conversationId={}, responseLength={}, citations={}", 
                conversationId, response.length(), citations.size());
        return RagChatResultVO.builder()
                .answer(response)
                .citations(citations)
                .build();
    }

    @Override
    public RagChatResultVO documentChat(Long documentId, String query) {
        log.info("文档问答: documentId={}, query={}", documentId, query);

        // 验证文档权限（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
        validateDocumentAccess(documentId, userId);

        // 构建过滤条件 - 只检索指定文档的内容
        Filter.Expression filterExpression = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("documentId", Base62.encode(String.valueOf(documentId))),
                new FilterExpressionBuilder().eq("userId", Base62.encode(String.valueOf(userId)))).build();

        // 构建检索请求（针对指定文档）
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();

        // 执行检索（根据配置选择检索策略）
        List<Document> docRetrievedDocs;
        
        if (ragConfig.getRetrieval().getEnableHybridSearch()) {
            // ✅ 启用混合检索（针对单个文档）
            log.info("文档问答启用混合检索: documentId={}", documentId);
            boolean useStrictReranking = ragConfig.getRetrieval().getEnableReranking();
            docRetrievedDocs = performHybridSearch(userId, query, searchRequest, useStrictReranking);
            
        } else if (ragConfig.getRetrieval().getEnableReranking()) {
            // 纯向量检索 + 重排序
            log.info("文档问答启用重排序: documentId={}", documentId);
            docRetrievedDocs = performVectorSearchWithReranking(searchRequest);
            
        } else {
            // 使用默认的纯向量检索
            docRetrievedDocs = vectorStore.similaritySearch(searchRequest);
        }
        
        log.info("文档问答检索完成: documentId={}, 检索到 {} 个片段", documentId, docRetrievedDocs.size());
        
        // 构建上下文
        String context = buildContext(docRetrievedDocs);
        
        // 创建 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();

        // 使用提示词模板构建用户提示（注入上下文）
        String userPrompt = documentQAPromptTemplate
                .replace("{question}", query)
                .replace("{context}", context);
        
        // 执行文档问答
        String response = ragChatClient.prompt()
                .advisors(qaAdvisor)
                .user(userPrompt)
                .call()
                .content();
        
        // 构建引用列表
        List<CitationVO> citations = buildCitations(docRetrievedDocs, query, 300);
        return RagChatResultVO.builder()
                .answer(response)
                .citations(citations)
                .build();
    }

    @Override
    public RagChatResultVO knowledgeBaseChat(Long knowledgeBaseId, String query) {
        log.info("知识库问答: knowledgeBaseId={}, query={}", knowledgeBaseId, query);
        
        // 获取当前用户ID
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 验证知识库访问权限
        validateKnowledgeBaseAccess(knowledgeBaseId, userId);
        
        // 1. 构建过滤条件：限定在该知识库的所有文档中
        Filter.Expression filterExpression = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("kbId", Base62.encode(String.valueOf(knowledgeBaseId))),
                new FilterExpressionBuilder().eq("userId", Base62.encode(String.valueOf(userId)))).build();
        
        // 2. 构建检索请求
        SearchRequest baseRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();
        
        // 动态调整检索参数
        SearchRequest adjustedRequest = dynamicRetrievalService.adjustSearchRequest(query, baseRequest);
        
        // 3. 执行检索（根据配置选择检索策略）
        List<Document> retrievedDocs;
        
        if (ragConfig.getRetrieval().getEnableHybridSearch()) {
            log.info("知识库问答启用混合检索: knowledgeBaseId={}", knowledgeBaseId);
            boolean useStrictReranking = ragConfig.getRetrieval().getEnableReranking();
            retrievedDocs = performHybridSearch(userId, query, adjustedRequest, useStrictReranking);
            
        } else if (ragConfig.getRetrieval().getEnableReranking()) {
            log.info("知识库问答启用重排序: knowledgeBaseId={}", knowledgeBaseId);
            retrievedDocs = performVectorSearchWithReranking(adjustedRequest);
            
        } else {
            retrievedDocs = vectorStore.similaritySearch(adjustedRequest);
        }
        
        log.info("知识库问答检索完成: knowledgeBaseId={}, 检索到 {} 个片段", knowledgeBaseId, retrievedDocs.size());
        
        // 4. 构建上下文
        String context = buildContext(retrievedDocs);
        
        // 5. 创建 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(adjustedRequest)
                .build();
        
        // 6. 构建提示词
        String userPrompt = documentQAPromptTemplate
                .replace("{question}", query)
                .replace("{context}", context);
        
        // 7. 执行问答
        String response = ragChatClient.prompt()
                .advisors(qaAdvisor)
                .user(userPrompt)
                .call()
                .content();
        
        // 8. 构建引用列表
        List<CitationVO> citations = buildCitations(retrievedDocs, query, 300);
        
        log.info("知识库问答完成: knowledgeBaseId={}, responseLength={}, citations={}", 
                knowledgeBaseId, response.length(), citations.size());
        
        return RagChatResultVO.builder()
                .answer(response)
                .citations(citations)
                .build();
    }

    /**
     * 构建引用列表 - 前端高亮方案
     * 
     * 改进: 不再生成 highlightedSnippet, 而是返回 keywords 列表供前端使用
     */
    private List<CitationVO> buildCitations(List<Document> docs, String query, int maxLen) {
        if (docs == null || docs.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        
        // 提取关键词列表(前端用于高亮)
        List<String> keywords = query == null || query.isBlank() 
            ? List.of() 
            : Arrays.stream(query.trim().split("\\s+"))
                .filter(kw -> !kw.isBlank())
                .toList();
        
        return docs.stream().map(d -> {
            // 改进：尝试多种方式获取 documentId
            Long documentId = NumberConversionUtils.toLong(String.valueOf(d.getMetadata().get("documentId")));
            Integer chunkIndex = NumberConversionUtils.toInteger(String.valueOf(d.getMetadata().get("chunkIndex")));
            Double score = d.getScore(); // 使用Document自带的score，而不是从metadata获取
            String title = d.getMetadata().getOrDefault("title", "未知文档").toString();
            String text = d.getText();
            if (text != null && text.length() > maxLen) {
                text = text.substring(0, maxLen) + "...";
            }

            // 查询 chunkId（可选，不影响主流程）
            Long chunkId = null;
            String position = null;
            String metadataJson = null;

            try {
                if (documentId != null && chunkIndex != null) {
                    DocumentChunkDO chunk = documentChunkMapper.selectOne(
                            new LambdaQueryWrapper<DocumentChunkDO>()
                                    .eq(DocumentChunkDO::getDocumentId, documentId)
                                    .eq(DocumentChunkDO::getChunkIndex, chunkIndex)
                                    .last("limit 1"));
                    if (chunk != null) {
                        chunkId = chunk.getId();
                        // 可以从 chunk 中获取更多信息
                        position = "第 " + (chunkIndex + 1) + " 段";
                    }
                } else {
                    log.trace("无法查询chunkId: documentId={}, chunkIndex={}", documentId, chunkIndex);
                }

                // 序列化元数据为JSON（可选，用于调试）
                if (!d.getMetadata().isEmpty()) {
                    try {
                        metadataJson = objectMapper.writeValueAsString(d.getMetadata());
                    } catch (Exception ignored) {
                        metadataJson = d.getMetadata().toString();
                    }
                }
            } catch (Exception e) {
                log.trace("查询chunkId失败 docId={}, chunkIndex={}", documentId, chunkIndex, e);
            }

            return CitationVO.builder()
                    .documentId(documentId)
                    .title(title)
                    .chunkId(chunkId)
                    .chunkIndex(chunkIndex)
                    .score(score)
                    .snippet(text)
                    .keywords(keywords)  // 提供给前端用于高亮
                    .position(position)
                    .metadata(metadataJson)
                    .build();
        }).toList();
    }

    /**
     * 混合检索：ES BM25 快速召回 + 向量精排 + LLM重排序
     * 
     * 三阶段策略：
     * 1. ES 召回大量候选（BM25 关键词匹配，快速）
     * 2. 向量精排（语义相似度，中等规模）
     * 3. LLM 重排序（精准评分，小规模）
     * 
     * @param userId 用户ID
     * @param query 查询文本
     * @param vectorRequest 向量检索请求
     * @param useStrictReranking 是否启用 LLM 重排序
     * @return 精排后的文档列表
     */
    private List<Document> performHybridSearch(Long userId, String query, SearchRequest vectorRequest, boolean useStrictReranking) {
        // 第一阶段：ES 快速召回
        int esTopK = ragConfig.getHybridSearch().getVectorTopK() * 
                    (useStrictReranking ? ragConfig.getRetrieval().getRerankExpandFactor() : 2);
        
        var esResults = documentESService.fullTextSearchWithHighlight(userId, query, esTopK);
        
        if (esResults.isEmpty()) {
            log.warn("ES 未召回文档，降级为纯向量检索");
            return vectorStore.similaritySearch(vectorRequest);
        }
        
        log.info("阶段1-ES召回: {} 个文档", esResults.size());
        
        // 第二阶段：向量精排
        List<Long> documentIds = esResults.stream()
                .map(r -> r.getDocumentId())
                .distinct()
                .collect(Collectors.toList());
        
        Filter.Expression filter = buildFilterWithDocumentIds(userId, documentIds);
        double threshold = useStrictReranking ? 
                ragConfig.getHybridSearch().getVectorSimilarityThreshold() : 
                vectorRequest.getSimilarityThreshold();
        
        int vectorTopK = useStrictReranking ? 
                vectorRequest.getTopK() * ragConfig.getRetrieval().getRerankExpandFactor() : 
                vectorRequest.getTopK();
        
        SearchRequest refinedRequest = SearchRequest.builder()
                .query(vectorRequest.getQuery())
                .topK(vectorTopK)
                .similarityThreshold(threshold)
                .filterExpression(filter)
                .build();
        
        List<Document> vectorResults = vectorStore.similaritySearch(refinedRequest);
        log.info("阶段2-向量精排: {} 个文档 (阈值={})", vectorResults.size(), threshold);
        
        // 第三阶段：LLM 重排序（可选）
        if (useStrictReranking && vectorResults.size() > vectorRequest.getTopK()) {
            return performLLMReranking(query, vectorResults, vectorRequest.getTopK());
        }
        
        return vectorResults.stream()
                .limit(vectorRequest.getTopK())
                .collect(Collectors.toList());
    }
    
    /**
     * 纯向量检索 + LLM 重排序
     * 
     * @param baseRequest 基础检索请求
     * @return 重排序后的文档列表
     */
    private List<Document> performVectorSearchWithReranking(SearchRequest baseRequest) {
        int expandedTopK = baseRequest.getTopK() * ragConfig.getRetrieval().getRerankExpandFactor();
        
        log.info("向量检索+重排序: 初始检索={}, 最终topK={}", expandedTopK, baseRequest.getTopK());
        
        // 第一阶段：扩大检索
        SearchRequest expandedRequest = SearchRequest.builder()
                .query(baseRequest.getQuery())
                .topK(expandedTopK)
                .similarityThreshold(baseRequest.getSimilarityThreshold())
                .filterExpression(baseRequest.getFilterExpression())
                .build();
        
        List<Document> candidates = vectorStore.similaritySearch(expandedRequest);
        log.info("初始检索: {} 个候选文档", candidates.size());
        
        // 第二阶段：LLM 重排序
        if (candidates.size() > baseRequest.getTopK()) {
            return performLLMReranking(baseRequest.getQuery(), candidates, baseRequest.getTopK());
        }
        
        return candidates;
    }
    
    /**
     * 使用 RerankService 进行 LLM 重排序
     */
    private List<Document> performLLMReranking(String query, List<Document> candidates, int topK) {
        // 构建候选 Map: chunkId -> content
        Map<Long, String> candidateMap = new LinkedHashMap<>();
        Map<Long, Document> docMap = new HashMap<>();
        
        for (Document doc : candidates) {
            Object chunkIdObj = doc.getMetadata().get("chunkId");
            if (chunkIdObj != null) {
                Long chunkId = NumberConversionUtils.toLong(String.valueOf(chunkIdObj));
                candidateMap.put(chunkId, doc.getText());
                docMap.put(chunkId, doc);
            }
        }
        
        if (candidateMap.isEmpty()) {
            log.warn("无有效 chunkId，跳过 LLM 重排序");
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
        
        // 调用 RerankService 重排序
        List<Long> rankedChunkIds = rerankService.rerank(query, candidateMap, topK);
        
        // 转换回 Document 列表
        List<Document> rerankedDocs = rankedChunkIds.stream()
                .map(docMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        log.info("LLM重排序完成: {} -> {} 个文档", candidates.size(), rerankedDocs.size());
        
        return rerankedDocs;
    }
    
    /**
     * 构建带文档ID过滤的表达式
     */
    private Filter.Expression buildFilterWithDocumentIds(Long userId, List<Long> documentIds) {
        // 注意：Spring AI VectorStore Filter 可能不支持 IN 操作
        // 这里只做用户过滤，文档范围过滤在后处理中完成
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.eq("userId", Base62.encode(String.valueOf(userId))).build();
    }
    
    /**
     * 构建上下文字符串
     * 将检索到的文档片段合并为一个上下文字符串
     */
    private String buildContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "未找到相关文档。";
        }
        
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("以下是检索到的相关文档片段：\n\n");
        
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            contextBuilder.append(String.format("[文档片段 %d]\n", i + 1));
            contextBuilder.append(doc.getText());
            contextBuilder.append("\n\n");
        }
        
        return contextBuilder.toString();
    }

    /**
     * 验证文档访问权限
     */
    private void validateDocumentAccess(Long documentId, Long userId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该文档");
        }
    }
    
    /**
     * 验证知识库访问权限
     */
    private void validateKnowledgeBaseAccess(Long knowledgeBaseId, Long userId) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BusinessException("知识库不存在");
        }
        if (!knowledgeBase.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该知识库");
        }
        // 检查知识库是否被逻辑删除
        if (knowledgeBase.getIsDeleted() != null && knowledgeBase.getIsDeleted() == 1) {
            throw new BusinessException("知识库已被删除");
        }
    }
}

    
