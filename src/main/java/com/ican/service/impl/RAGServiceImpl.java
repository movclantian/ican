package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base62;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ican.config.RAGConfig;
import com.ican.mapper.DocumentMapper;
import com.ican.mapper.TeachingPlanMapper;
import com.ican.model.entity.DocumentDO;
import com.ican.model.entity.TeachingPlanDO;
import com.ican.model.vo.CitationVO;
import com.ican.model.vo.InnovationClusterVO;
import com.ican.model.vo.PaperComparisonVO;
import com.ican.model.vo.PaperMetadataVO;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.RagAnswerVO;
import com.ican.model.vo.RagChatResultVO;
import com.ican.model.vo.TeachingPlanListVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.mapper.DocumentChunkMapper;
import com.ican.model.entity.DocumentChunkDO;
import com.ican.service.RAGService;
import com.ican.service.DynamicRetrievalService;
import com.ican.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 服务实现类
 *
 * @author ICan
 * @since 2024-10-06
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
    private final TeachingPlanMapper teachingPlanMapper;
    private final DynamicRetrievalService dynamicRetrievalService;
    private final RerankService rerankService;

    // 注入 Redis 用于缓存元数据
    private final RedisTemplate<String, Object> redisTemplate;

    // 注入 ChatClient Bean
    private final ChatClient ragChatClient;

    // 注入提示词模板
    @Qualifier("ragQAPromptTemplate")
    private final String ragQAPromptTemplate;
    @Qualifier("documentQAPromptTemplate")
    private final String documentQAPromptTemplate;
    @Qualifier("teachingPlanPromptTemplate")
    private final String teachingPlanPromptTemplate;
    @Qualifier("keywordExtractionPromptTemplate")
    private final String keywordExtractionPromptTemplate;
    @Qualifier("paperSummaryPromptTemplate")
    private final String paperSummaryPromptTemplate;
    @Qualifier("innovationExtractionPromptTemplate")
    private final String innovationExtractionPromptTemplate;
    @Qualifier("paperComparisonPromptTemplate")
    private final String paperComparisonPromptTemplate;
    @Qualifier("innovationClusteringPromptTemplate")
    private final String innovationClusteringPromptTemplate;
    @Qualifier("paperMetadataExtractionPromptTemplate")
    private final String paperMetadataExtractionPromptTemplate;


    // 动态选项配置
    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private Integer maxTokens;

    // JSON 转换器
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Redis 缓存键前缀和过期时间
    private static final String PAPER_METADATA_CACHE_PREFIX = "paper:metadata:";
    private static final long CACHE_EXPIRE_DAYS = 30; // 缓存30天

    @Override
    public RagChatResultVO ragChat(String conversationId, String query) {
        log.info("RAG问答: conversationId={}, query={}", conversationId, query);

        // 获取当前用户ID（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();

        // 1. 构建基础过滤条件
        Filter.Expression filterExpression = new FilterExpressionBuilder().eq("userId",
                Base62.encode(String.valueOf(userId))).build();

        // 2. 优化检索策略：提取核心关键词，使用多查询策略
        List<String> searchQueries = extractSearchQueries(query);
        log.info("RAG问答检索优化: 原始query='{}', 提取的查询词={}", query, searchQueries);

        // 3. 使用多个查询进行检索，合并结果（使用动态调整的阈值）
        SearchRequest baseRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();

        // 动态调整检索参数
        SearchRequest adjustedRequest = dynamicRetrievalService.adjustSearchRequest(query, baseRequest);
        log.debug("动态检索调整: topK {} -> {}, threshold {} -> {}",
                baseRequest.getTopK(), adjustedRequest.getTopK(),
                baseRequest.getSimilarityThreshold(), adjustedRequest.getSimilarityThreshold());

        // 使用调整后的参数进行多查询检索
        List<Document> finalDocs = performMultiQuerySearch(
                searchQueries,
                filterExpression,
                adjustedRequest.getTopK(),
                adjustedRequest.getSimilarityThreshold());

        log.info("RAG问答多查询检索完成: 共检索到 {} 个文档片段", finalDocs.size());

        // 4. 如果启用重排序，对检索结果进行重排
        if (ragConfig.getRetrieval().getEnableReranking() && !finalDocs.isEmpty()) {
            log.debug("RAG问答开始重排序");
            finalDocs = rerankDocuments(finalDocs, query, adjustedRequest.getTopK());
            log.info("RAG问答重排序完成: 保留 {} 个文档片段", finalDocs.size());
        }

        // 创建 Advisor（使用调整后的参数）
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(adjustedRequest)
                .build();

        // 使用提示词模板构建用户提示
        String userPrompt = ragQAPromptTemplate.replace("{question}", query);

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

        // 使用注入的 ragChatClient 执行 RAG 问答（包含历史对话）
        String response = ragChatClient.prompt()
                .advisors(qaAdvisor) // 动态添加用户特定的 Advisor
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

        // 记录查询日志

        // 构建引用列表（使用最终文档）
        List<CitationVO> citations = buildCitations(finalDocs, query, 300);

        log.info("RAG问答完成: conversationId={}, responseLength={}, citations={} ", conversationId, response.length(),
                citations.size());
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

        // 优化检索策略：提取核心关键词，使用多查询策略
        List<String> searchQueries = extractSearchQueries(query);
        log.info("文档问答检索优化: 原始query='{}', 提取的查询词={}", query, searchQueries);

        // 使用多个查询进行检索，合并结果
        List<Document> retrievedDocs = performMultiQuerySearch(
                searchQueries,
                filterExpression,
                ragConfig.getRetrieval().getTopK(),
                ragConfig.getRetrieval().getSimilarityThreshold());

        log.info("文档问答多查询检索完成: 共检索到 {} 个文档片段", retrievedDocs.size());

        // 如果启用重排序，对检索结果进行重排
        if (ragConfig.getRetrieval().getEnableReranking() && !retrievedDocs.isEmpty()) {
            log.debug("文档问答开始重排序");
            retrievedDocs = rerankDocuments(retrievedDocs, query, ragConfig.getRetrieval().getTopK());
            log.info("文档问答重排序完成: 保留 {} 个片段", retrievedDocs.size());
        }

        // 构建基础SearchRequest用于QuestionAnswerAdvisor（使用原始query）
        SearchRequest baseRequest = SearchRequest.builder()
                .query(query)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();

        // 创建针对指定文档的 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(baseRequest)
                .build();

        // 使用提示词模板构建用户提示
        String userPrompt = documentQAPromptTemplate.replace("{question}", query);
        Prompt prompt = new Prompt(userPrompt);
        // 使用注入的 ragChatClient 执行文档问答
        String response = ragChatClient.prompt(prompt)
                .advisors(qaAdvisor) // 动态添加文档特定的 Advisor
                .call()
                .content();
        List<CitationVO> citations = buildCitations(retrievedDocs, query, 300);
        return RagChatResultVO.builder().answer(response).citations(citations).build();
    }

    @Override
    public RagAnswerVO<PaperSummaryVO> summarizePaper(Long documentId) {
        log.info("论文总结: documentId={}", documentId);

        // 验证文档权限（拦截器已确保用户已登录）
        Long userId = StpUtil.getLoginIdAsLong();
        validateDocumentAccess(documentId, userId);

        // 获取所有分块
        List<DocumentChunkDO> allChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkDO>()
                        .eq(DocumentChunkDO::getDocumentId, documentId)
                        .eq(DocumentChunkDO::getIsDeleted, 0)
                        .orderByAsc(DocumentChunkDO::getChunkIndex)
        );

        if (allChunks.isEmpty()) {
            throw new BusinessException("文档内容为空，无法生成总结");
        }

        log.info("获取到 {} 个分块用于生成总结", allChunks.size());

        try {
            // 【策略选择】根据文档长度选择不同的总结策略
            String contextContent;
            
            if (allChunks.size() <= 10) {
                // 短文档：直接总结（<= 10个chunk，约5000字符）
                log.debug("使用直接总结策略（短文档）");
                contextContent = buildDirectContext(allChunks);
            } else {
                // 长文档：两阶段总结（> 10个chunk）
                log.debug("使用两阶段总结策略（长文档，共{}个chunk）", allChunks.size());
                contextContent = buildTwoStageSummary(allChunks);
            }

            // 使用提示词模板构建完整提示
            String fullPrompt = paperSummaryPromptTemplate.replace("{context}", contextContent);

            // 增强system message,强调JSON格式和内容完整性
            String systemMessage = """
                你是一位资深的学术论文分析专家，拥有多年的学术审稿和论文写作经验。
                
                核心要求：
                1. 必须返回完整填充的JSON对象，所有字段都要有实质性内容
                2. 不允许返回空字符串("")或空数组([])
                3. 基于论文内容深度分析，每个描述性字段至少200字
                4. 只返回纯JSON，绝对不要markdown标记（如```json）
                5. 如果某些信息在文中不明确，请根据上下文合理推断和概括
                
                记住：你的目标是生成一份完整、详细、有价值的论文总结，而不是空壳结构！
                """;
            // 直接调用LLM，不使用QuestionAnswerAdvisor
            String rawResponse = ragChatClient.prompt()
                    .system(systemMessage)
                    .user(fullPrompt)
                    .call()
                    .content();

            // 【关键】详细记录原始响应
            log.info("===== LLM原始响应（完整内容） =====");
            log.info("响应长度: {} 字符", rawResponse != null ? rawResponse.length() : 0);
            if (rawResponse != null && rawResponse.length() > 0) {
                log.info("前500字符:\n{}", rawResponse.substring(0, Math.min(500, rawResponse.length())));
                log.debug("完整响应:\n{}", rawResponse);
            }
            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                log.error("【致命错误】LLM返回空响应! documentId={}", documentId);
                log.error("请检查: 1) OpenAI API key是否有效 2) 网络连接 3) 模型是否可用 4) Token额度");
                throw new BusinessException("LLM服务返回空响应，请检查服务配置");
            }

            // 预处理响应内容
            String cleanedResponse = cleanJsonResponse(rawResponse);
            // 手动解析 JSON 为 PaperSummaryVO
            PaperSummaryVO summary;
            try {
                summary = objectMapper.readValue(cleanedResponse, PaperSummaryVO.class);
                if (summary == null) {
                    throw new BusinessException("LLM返回的总结数据为空");
                }
                log.info("✓ JSON解析成功: title={}, authors={}, year={}",
                        summary.getTitle(), 
                        summary.getAuthors() != null ? summary.getAuthors().size() : 0, 
                        summary.getPublicationYear());
            } catch (BusinessException be) {
                throw be;
            } catch (Exception parseEx) {
                log.error("✗ JSON解析失败! 原始响应: {}", rawResponse);
                log.error("清理后的JSON: {}", cleanedResponse);
                log.error("解析异常详情", parseEx);
                throw new BusinessException("论文总结JSON解析失败: " + parseEx.getMessage());
            }

            // 验证和填充默认值，防止null字段
            summary.validate();

            // 查询文档标题
            DocumentDO document = documentMapper.selectById(documentId);
            String documentTitle = (document != null && document.getTitle() != null)
                    ? document.getTitle() : "未知文档";

            // 构建引用列表（使用检索到的chunks构建Document列表）
            // 注意：这里的Documents是从数据库直接获取的，不是向量搜索的结果，所以没有相似度分数
            List<Document> retrievedDocs = new ArrayList<>();
            for (int i = 0; i < allChunks.size(); i++) {
                DocumentChunkDO chunk = allChunks.get(i);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", String.valueOf(documentId));
                metadata.put("chunkIndex", chunk.getChunkIndex());
                metadata.put("title", documentTitle); // 添加标题到metadata

                Document doc = Document.builder()
                        .id(chunk.getVectorId())
                        .score(1.0)
                        .text(chunk.getContent())
                        .metadata(metadata)
                        .build();
                retrievedDocs.add(doc);
            }

            List<CitationVO> citations = buildCitations(retrievedDocs, "论文总结", 500);

            log.info("论文总结完成: documentId={}, citations={}", documentId, citations.size());

            return RagAnswerVO.<PaperSummaryVO>builder()
                    .answer(summary)
                    .citations(citations)
                    .build();
        } catch (Exception e) {
            log.error("论文总结失败: documentId={}", documentId, e);
            throw new BusinessException("论文总结失败: " + e.getMessage());
        }
    }

    @Override
    public RagAnswerVO<TeachingPlanVO> generateTeachingPlan(String topic, String grade, String subject,
                                                            List<Long> documentIds) {
        log.info("生成教学设计: topic={}, grade={}, subject={}, documentIds={}", topic, grade, subject, documentIds);

        // 获取当前用户ID
        Long userId = StpUtil.getLoginIdAsLong();

        // TP-01: 使用 documentIds - 优先检索指定文档
        Filter.Expression filterExpression;
        if (documentIds != null && !documentIds.isEmpty()) {
            // 同时过滤 userId 和 documentIds（使用 Base62 编码）
            String[] encodedDocIds = documentIds.stream()
                    .map(id -> Base62.encode(String.valueOf(id)))
                    .toArray(String[]::new);
            filterExpression = new FilterExpressionBuilder()
                    .and(
                            new FilterExpressionBuilder().eq("userId", Base62.encode(String.valueOf(userId))),
                            new FilterExpressionBuilder().in("documentId", (Object[]) encodedDocIds))
                    .build();
        } else {
            // 只过滤 userId
            filterExpression = new FilterExpressionBuilder().eq("userId",
                    Base62.encode(String.valueOf(userId))).build();
        }

        // 优化检索策略：提取核心关键词，使用多查询策略
        List<String> searchQueries = extractSearchQueries(topic);
        log.info("检索查询优化: 原始topic='{}', 提取的查询词={}", topic, searchQueries);

        // 使用多个查询进行检索，合并结果
        List<Document> retrievedDocs = performMultiQuerySearch(searchQueries, filterExpression, 10, 0.5);

        log.info("多查询检索完成: 共检索到 {} 个文档片段", retrievedDocs.size());

        // 如果启用重排序，对检索结果进行重排
        if (ragConfig.getRetrieval().getEnableReranking() && !retrievedDocs.isEmpty()) {
            log.debug("开始对检索结果进行重排序");
            retrievedDocs = rerankDocuments(retrievedDocs, topic, 10);
            log.info("重排序完成: 保留 {} 个片段", retrievedDocs.size());
        }

        // 构建一个基础SearchRequest用于QuestionAnswerAdvisor（使用原始topic）
        SearchRequest baseRequest = SearchRequest.builder()
                .query(topic)
                .similarityThreshold(0.5)
                .topK(10)
                .filterExpression(filterExpression)
                .build();

        // 使用 QuestionAnswerAdvisor 进行 RAG
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(baseRequest)
                .build();

        try {
            // 使用注入的教学设计提示词模板，替换占位符
            String userPrompt = teachingPlanPromptTemplate
                    .replace("{topic}", topic)
                    .replace("{grade}", grade)
                    .replace("{subject}", subject);

            log.info("===== 教学设计生成 - 开始调用LLM =====");
            // 执行RAG并获取原始响应
            String rawResponse = ragChatClient.prompt()
                    .advisors(qaAdvisor) // 动态添加 Advisor
                    .system("你是一位经验丰富的教学设计专家。请根据提供的参考资料和要求，生成完整详细的教学设计方案。必须严格按照指定的JSON格式返回，所有字段都必须填写完整，不要使用占位符或空数组。")
                    .user(userPrompt)
                    .call()
                    .content();

            log.info("===== LLM响应接收完成 =====");
            // 手动解析 JSON 为 TeachingPlanVO
            TeachingPlanVO teachingPlan;
            try {
                teachingPlan = objectMapper.readValue(rawResponse, TeachingPlanVO.class);
            } catch (Exception parseEx) {
                log.error("===== JSON解析失败 =====");
                log.error("完整响应内容: {}", rawResponse);
                log.error("解析异常详情", parseEx);
                throw new BusinessException("解析教学设计失败: " + parseEx.getMessage());
            }

            // 验证和填充默认值，防止null字段
            teachingPlan.validate();
            // TP-02: 构建引用列表（来源文档）
            List<CitationVO> citations = buildCitations(retrievedDocs, topic, 300);
            log.info("教学设计生成完成: topic={}, citations={}", topic, citations.size());
            return RagAnswerVO.<TeachingPlanVO>builder()
                    .answer(teachingPlan)
                    .citations(citations)
                    .build();
        } catch (Exception e) {
            log.error("教学设计生成失败: topic={}", topic, e);
            throw new BusinessException("教学设计生成失败,请稍后重试");
        }
    }

    /**
     * 构建引用列表
     */
    private List<CitationVO> buildCitations(List<Document> docs, String query, int maxLen) {
        if (docs == null || docs.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String[] keywords = query == null ? new String[0] : query.trim().split("\\s+");
        return docs.stream().map(d -> {
            // 改进：尝试多种方式获取 documentId
            Long documentId = toLong(d.getMetadata().get("documentId"));
            if (documentId == null) {
                // 尝试从 doc_id 或其他可能的键获取
                documentId = toLong(d.getMetadata().get("doc_id"));
            }

            Integer chunkIndex = toInteger(d.getMetadata().get("chunkIndex"));
            if (chunkIndex == null) {
                chunkIndex = toInteger(d.getMetadata().get("chunk_index"));
            }

            Double score = d.getScore(); // 使用Document自带的score，而不是从metadata获取
            String title = d.getMetadata().getOrDefault("title", "未知文档").toString();
            String text = d.getText();
            if (text != null && text.length() > maxLen) {
                text = text.substring(0, maxLen) + "...";
            }
            String highlighted = highlight(text, keywords);

            // 查询 chunkId（可选，不影响主流程）
            Long chunkId = null;
            String position = null;
            String metadataJson = null;

            try {
                if (documentId != null && chunkIndex != null) {
                    DocumentChunkDO chunk = documentChunkMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentChunkDO>()
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
                    .highlightedSnippet(highlighted)
                    .position(position)  // 添加位置信息
                    .metadata(metadataJson)  // 添加元数据
                    .build();
        }).toList();
    }

    /**
     * 将对象转换为 Long 类型
     * 支持从 Base62 编码的字符串解码（避免向量库 Double 精度丢失）
     */
    private Long toLong(Object o) {
        try {
            if (o == null) {
                return null;
            }

            // 优先处理 Base62 编码的字符串（新格式）
            if (o instanceof String) {
                String str = ((String) o).trim();
                try {
                    // 尝试 Base62 解码
                    String decoded = Base62.decodeStr(str);
                    return Long.valueOf(decoded);
                } catch (Exception e) {
                    // 如果不是 Base62，尝试直接解析
                    if (str.matches("\\d+")) {
                        return Long.valueOf(str);
                    }
                    // 包含小数点或科学计数法（兼容旧数据）
                    if (str.contains(".") || str.toLowerCase().contains("e")) {
                        return Double.valueOf(str).longValue();
                    }
                    throw e;
                }
            }

            // 处理Double类型（旧数据兼容，可能有精度丢失）
            if (o instanceof Double) {
                return ((Double) o).longValue();
            }

            // 处理其他数值类型
            if (o instanceof Float) {
                return ((Float) o).longValue();
            }
            if (o instanceof Long) {
                return (Long) o;
            }
            if (o instanceof Number) {
                return ((Number) o).longValue();
            }

            // 最后尝试 toString 并解析
            String str = o.toString().trim();
            try {
                String decoded = Base62.decodeStr(str);
                return Long.valueOf(decoded);
            } catch (Exception e) {
                if (str.matches("\\d+")) {
                    return Long.valueOf(str);
                }
                if (str.contains(".") || str.toLowerCase().contains("e")) {
                    return Double.valueOf(str).longValue();
                }
                return Long.valueOf(str);
            }
        } catch (Exception e) {
            log.trace("toLong转换失败: value={}, type={}", o, o != null ? o.getClass() : "null", e);
            return null;
        }
    }

    private Integer toInteger(Object o) {
        try {
            if (o == null)
                return null;
            // 处理Double类型（向量库可能返回Double）
            if (o instanceof Double) {
                return ((Double) o).intValue();
            }
            // 处理Float类型
            if (o instanceof Float) {
                return ((Float) o).intValue();
            }
            // 处理Number类型
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
            // 尝试解析字符串（包括科学计数法）
            String str = o.toString().trim();
            if (str.contains(".") || str.toLowerCase().contains("e")) {
                return Double.valueOf(str).intValue();
            }
            return Integer.valueOf(str);
        } catch (Exception e) {
            log.trace("toInteger转换失败: value={}, type={}", o, o != null ? o.getClass() : "null", e);
            return null;
        }
    }

    /**
     * 高亮关键词（忽略大小写）
     */
    private String highlight(String text, String[] keywords) {
        if (keywords == null || keywords.length == 0)
            return text;
        String result = text;
        for (String kw : keywords) {
            if (kw.isBlank())
                continue;
            try {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(kw), "<mark>$0</mark>");
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 从topic中提取核心关键词，生成多个检索查询
     * 策略：优先使用LLM提取（保证质量），失败时降级到启发式规则
     */
    private List<String> extractSearchQueries(String topic) {
        List<String> queries = new ArrayList<>();

        try {
            // 第一步：使用LLM提取关键词（主要方案）
            String prompt = keywordExtractionPromptTemplate.replace("{topic}", topic);

            String response = ragChatClient.prompt()
                    .system("你是一个专业的信息检索专家，擅长快速提取核心关键词。")
                    .user(prompt)
                    .call()
                    .content();

            // 解析JSON数组
            String cleanJson = extractJsonArray(response);
            List<String> keywords = objectMapper.readValue(cleanJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

            // 添加提取的关键词（去重，限制数量）
            if (keywords != null && !keywords.isEmpty()) {
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isBlank() && !keyword.equals(topic)) {
                        queries.add(keyword.trim());
                    }
                }
                log.debug("LLM提取关键词: {} -> {}", topic, keywords);
                return queries;
            }

            // 如果LLM返回空，降级到启发式方法
            log.debug("LLM未返回关键词，降级到启发式提取");
            List<String> heuristicKeywords = extractKeywordsByHeuristic(topic);
            if (!heuristicKeywords.isEmpty()) {
                queries.addAll(heuristicKeywords);
                log.debug("启发式提取关键词: {} -> {}", topic, heuristicKeywords);
            }

            return queries;

        } catch (Exception e) {
            // LLM调用失败，降级到启发式方法
            log.warn("LLM提取关键词失败，降级到启发式方法: {}", topic, e);
            try {
                List<String> heuristicKeywords = extractKeywordsByHeuristic(topic);
                if (!heuristicKeywords.isEmpty()) {
                    queries.addAll(heuristicKeywords);
                    log.debug("启发式提取关键词（降级）: {} -> {}", topic, heuristicKeywords);
                }
            } catch (Exception he) {
                log.warn("启发式提取也失败，只使用原始topic: {}", topic, he);
            }
            return queries;
        }
    }

    /**
     * 启发式规则快速提取关键词
     * 策略：分词 + 去除停用词 + 保留专业术语
     */
    private List<String> extractKeywordsByHeuristic(String topic) {
        List<String> keywords = new ArrayList<>();

        // 常见停用词（可扩展）
        Set<String> stopWords = Set.of(
                "的", "了", "在", "是", "有", "和", "与", "及", "或", "等",
                "研究", "分析", "探讨", "讨论", "论题", "课题", "主题",
                "教学", "设计", "方案", "计划", "内容",
                "应用", "实践", "案例", "方法", "技术"
        );

        // 简单分词：按空格、标点分割
        String[] tokens = topic.split("[\\s，。、；：\\(\\)\\[\\]《》]+");

        for (String token : tokens) {
            token = token.trim();
            // 保留长度>=2的词，且不在停用词表中
            if (token.length() >= 2 && !stopWords.contains(token)) {
                keywords.add(token);
            }
        }

        // 如果有英文单词，单独提取（通常是专业术语）
        Pattern pattern = Pattern.compile("[a-zA-Z]{3,}");
        Matcher matcher = pattern.matcher(topic);
        while (matcher.find()) {
            String englishWord = matcher.group();
            if (!keywords.contains(englishWord)) {
                keywords.add(englishWord);
            }
        }

        // 去重并限制数量
        return keywords.stream()
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
    }

    /**
     * 使用多个查询词进行并行检索，合并去重结果
     *
     * @param queries          查询词列表
     * @param filterExpression 过滤条件
     * @param topK             每个查询返回的最大结果数
     * @param threshold        相似度阈值
     * @return 合并去重后的文档列表
     */
    private List<Document> performMultiQuerySearch(List<String> queries, Filter.Expression filterExpression,
                                                   int topK, double threshold) {
        long startTime = System.currentTimeMillis();

        // 使用并行流同时执行多个查询
        Map<String, Document> docMap = queries.parallelStream()
                .flatMap(query -> {
                    try {
                        SearchRequest searchRequest = SearchRequest.builder()
                                .query(query)
                                .similarityThreshold(threshold)
                                .topK(topK)
                                .filterExpression(filterExpression)
                                .build();

                        List<Document> docs = vectorStore.similaritySearch(searchRequest);
                        log.debug("查询词 '{}' 检索到 {} 个文档", query, docs.size());

                        return docs.stream();
                    } catch (Exception e) {
                        log.warn("查询词 '{}' 检索失败", query, e);
                        return java.util.stream.Stream.empty();
                    }
                })
                // 去重：使用vectorId作为key，保留score最高的
                .collect(Collectors.toMap(
                        Document::getId,  // key: vectorId
                        doc -> doc,       // value: Document
                        (existing, replacement) -> {
                            // 冲突时保留score更高的
                            Double existingScore = existing.getScore();
                            Double replacementScore = replacement.getScore();

                            if (replacementScore != null &&
                                    (existingScore == null || replacementScore > existingScore)) {
                                return replacement;
                            }
                            return existing;
                        },
                        java.util.concurrent.ConcurrentHashMap::new  // 线程安全的Map
                ));

        // 按score降序排序
        List<Document> result = new ArrayList<>(docMap.values());
        result.sort((d1, d2) -> {
            Double score1 = d1.getScore();
            Double score2 = d2.getScore();
            if (score1 == null && score2 == null) return 0;
            if (score1 == null) return 1;
            if (score2 == null) return -1;
            return Double.compare(score2, score1); // 降序
        });

        // 限制最终返回数量
        if (result.size() > topK) {
            result = result.subList(0, topK);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.debug("并行多查询检索完成: {} 个查询词, 耗时 {} ms, 返回 {} 个结果",
                queries.size(), duration, result.size());

        return result;
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
     * 对检索到的文档进行重排序
     *
     * @param candidateDocs 候选文档列表
     * @param query         查询问题
     * @param topK          返回前K个结果
     * @return 重排序后的文档列表
     */
    private List<Document> rerankDocuments(List<Document> candidateDocs, String query, int topK) {
        // 通过vectorId查询chunkId
        Map<Long, String> candidates = new HashMap<>();
        Map<Long, String> chunkIdToVectorId = new HashMap<>();

        for (Document doc : candidateDocs) {
            String vectorId = doc.getId();
            DocumentChunkDO chunk = documentChunkMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentChunkDO>()
                            .eq(DocumentChunkDO::getVectorId, vectorId)
                            .eq(DocumentChunkDO::getIsDeleted, 0)
                            .last("limit 1"));

            if (chunk != null) {
                candidates.put(chunk.getId(), doc.getText());
                chunkIdToVectorId.put(chunk.getId(), vectorId);
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> rerankedChunkIds = rerankService.rerank(query, candidates, topK);

        return rerankedChunkIds.stream()
                .map(chunkId -> {
                    String vectorId = chunkIdToVectorId.get(chunkId);
                    return candidateDocs.stream()
                            .filter(doc -> doc.getId().equals(vectorId))
                            .findFirst()
                            .orElse(null);
                })
                .filter(doc -> doc != null)
                .collect(Collectors.toList());
    }

    /**
     * 构建直接总结的上下文（适用于短文档）
     * 直接拼接所有chunk内容，限制总长度
     */
    private String buildDirectContext(List<DocumentChunkDO> chunks) {
        StringBuilder contextBuilder = new StringBuilder("论文完整内容:\n\n");
        int totalLength = 0;
        int maxContextLength = 12000; // 短文档允许更长的上下文
        
        for (DocumentChunkDO chunk : chunks) {
            String content = chunk.getContent();
            
            // 检查是否超过总长度限制
            if (totalLength + content.length() > maxContextLength) {
                log.debug("直接总结上下文达到长度限制，已使用{}个分块", contextBuilder.toString().split("\n\n").length - 1);
                break;
            }
            
            contextBuilder.append(content).append("\n\n");
            totalLength += content.length();
        }
        
        log.debug("直接总结上下文构建完成，总长度: {} 字符", totalLength);
        return contextBuilder.toString();
    }

    /**
     * 两阶段总结策略（适用于长文档）
     * 第一阶段：将文档分批，生成中间总结
     * 第二阶段：汇总所有中间总结
     */
    private String buildTwoStageSummary(List<DocumentChunkDO> allChunks) {
        log.info("开始两阶段总结，共 {} 个chunk", allChunks.size());
        
        // 第一阶段：分批生成中间总结
        int batchSize = 5; // 每批5个chunk
        List<String> intermediateSummaries = new ArrayList<>();
        
        for (int i = 0; i < allChunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allChunks.size());
            List<DocumentChunkDO> batch = allChunks.subList(i, end);
            
            // 构建本批次的内容
            StringBuilder batchContent = new StringBuilder();
            for (DocumentChunkDO chunk : batch) {
                String content = chunk.getContent();
                // 限制单个chunk长度
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...";
                }
                batchContent.append(content).append("\n\n");
            }
            
            // 生成本批次的简要总结
            String batchSummary = generateIntermediateSummary(batchContent.toString(), i / batchSize + 1);
            if (batchSummary != null && !batchSummary.isBlank()) {
                intermediateSummaries.add(batchSummary);
            }
            
            log.debug("完成第 {} 批次总结（chunk {}-{}）", i / batchSize + 1, i, end - 1);
        }
        
        // 第二阶段：汇总所有中间总结
        StringBuilder finalContext = new StringBuilder("论文分段总结汇总:\n\n");
        for (int i = 0; i < intermediateSummaries.size(); i++) {
            finalContext.append("【第 ").append(i + 1).append(" 部分】\n");
            finalContext.append(intermediateSummaries.get(i)).append("\n\n");
        }
        
        log.info("两阶段总结完成，共生成 {} 个中间总结，汇总长度: {} 字符", 
                intermediateSummaries.size(), finalContext.length());
        
        return finalContext.toString();
    }

    /**
     * 生成中间总结（简洁版）
     */
    private String generateIntermediateSummary(String content, int batchNumber) {
        try {
            String prompt = String.format(
                "请对以下论文片段（第%d部分）进行简要总结，提取关键信息（200字以内）：\n\n%s\n\n" +
                "要求：\n" +
                "1. 只返回纯文本总结，不要JSON格式\n" +
                "2. 重点关注：研究方法、实验设计、主要发现、创新点\n" +
                "3. 保持简洁，200字以内",
                batchNumber, content
            );
            
            String summary = ragChatClient.prompt()
                    .system("你是论文分析专家，擅长提取关键信息。")
                    .user(prompt)
                    .call()
                    .content();
            
            return summary != null ? summary.trim() : "";
            
        } catch (Exception e) {
            log.warn("生成第 {} 批次中间总结失败", batchNumber, e);
            return "";
        }
    }

    /**
     * 保存教学设计
     */
    @Override
    public Long saveTeachingPlan(TeachingPlanVO teachingPlanVO) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();

        try {
            TeachingPlanDO teachingPlan = new TeachingPlanDO();
            teachingPlan.setUserId(userId);
            teachingPlan.setTitle(teachingPlanVO.getTitle());
            teachingPlan.setGrade(teachingPlanVO.getGrade());
            teachingPlan.setSubject(teachingPlanVO.getSubject());

            // 将TeachingPlanVO转换为JSON保存
            String contentJson = objectMapper.writeValueAsString(teachingPlanVO);
            teachingPlan.setContent(contentJson);

            // 保存关联的文档ID列表 (暂时不处理,TeachingPlanVO中没有该字段)
            // TeachingPlanDO会自动处理JSON字段

            teachingPlanMapper.insert(teachingPlan);
            log.info("教学设计保存成功: id={}, userId={}, title={}",
                    teachingPlan.getId(), userId, teachingPlanVO.getTitle());
            return teachingPlan.getId();
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON序列化失败: userId={}", userId, e);
            throw new BusinessException("教学设计保存失败");
        }
    }

    /**
     * 获取用户的教学设计列表
     */
    @Override
    public List<TeachingPlanListVO> getUserTeachingPlans(Long userId) {
        LambdaQueryWrapper<TeachingPlanDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TeachingPlanDO::getUserId, userId)
                .eq(TeachingPlanDO::getIsDeleted, 0)
                .orderByDesc(TeachingPlanDO::getUpdateTime);

        List<TeachingPlanDO> teachingPlans = teachingPlanMapper.selectList(queryWrapper);

        return teachingPlans.stream().map(plan -> {
            TeachingPlanListVO vo = new TeachingPlanListVO();
            vo.setId(plan.getId());
            vo.setTitle(plan.getTitle());
            vo.setGrade(plan.getGrade());
            vo.setSubject(plan.getSubject());
            vo.setCreateTime(plan.getCreateTime());
            vo.setUpdateTime(plan.getUpdateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 获取教学设计详情
     */
    @Override
    public TeachingPlanVO getTeachingPlan(Long planId) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();

        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }

        // 验证权限
        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该教学设计");
        }

        try {
            // 从JSON反序列化教学设计内容
            TeachingPlanVO vo = objectMapper.readValue(teachingPlan.getContent(), TeachingPlanVO.class);
            return vo;
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON反序列化失败: planId={}", planId, e);
            throw new BusinessException("教学设计数据解析失败");
        }
    }

    /**
     * 删除教学设计
     */
    @Override
    public void deleteTeachingPlan(Long planId) {
        // 从当前登录用户获取userId
        Long userId = StpUtil.getLoginIdAsLong();

        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }

        // 验证权限
        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该教学设计");
        }

        // 软删除
        teachingPlan.setIsDeleted(1);
        teachingPlanMapper.updateById(teachingPlan);
        log.info("教学设计删除成功: planId={}, userId={}", planId, userId);
    }

    /**
     * PA-01: 论文对比
     */
    @Override
    public RagAnswerVO<PaperComparisonVO> comparePapers(List<Long> documentIds, List<String> dimensions) {
        log.info("论文对比: documentIds={}, dimensions={}", documentIds, dimensions);

        // 验证文档权限
        Long userId = StpUtil.getLoginIdAsLong();
        // 批量查询文档（避免使用已废弃方法 selectBatchIds）
        List<DocumentDO> documents = documentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentDO>()
                        .in(DocumentDO::getId, documentIds)
                        .eq(DocumentDO::getIsDeleted, 0));

        if (documents.size() != documentIds.size()) {
            throw new BusinessException("部分文档不存在");
        }

        for (DocumentDO doc : documents) {
            if (!doc.getUserId().equals(userId)) {
                throw new BusinessException("无权访问文档: " + doc.getTitle());
            }
        }

        // 默认对比维度
        if (dimensions == null || dimensions.isEmpty()) {
            dimensions = List.of("研究方法", "使用数据集", "主要创新点", "研究结论", "实验结果");
        }

        // 优化：直接从数据库获取每篇论文的前10个chunk（避免重复向量检索）
        Map<Long, List<DocumentChunkDO>> paperChunksMap = new HashMap<>();
        for (Long docId : documentIds) {
            List<DocumentChunkDO> chunks = documentChunkMapper.selectList(
                    new LambdaQueryWrapper<DocumentChunkDO>()
                            .eq(DocumentChunkDO::getDocumentId, docId)
                            .eq(DocumentChunkDO::getIsDeleted, 0)
                            .orderByAsc(DocumentChunkDO::getChunkIndex)
                            .last("LIMIT 30") // 每篇论文只取前30个chunk，包含摘要、引言等核心内容
            );
            paperChunksMap.put(docId, chunks);
        }

        // 构建结构化的对比prompt
        StringBuilder contextBuilder = new StringBuilder();
        
        // 1. 首先明确输出格式要求（让LLM优先知道期望的输出）
        contextBuilder.append("【输出格式】\n");
        contextBuilder.append(paperComparisonPromptTemplate);
        contextBuilder.append("\n\n");
        
        // 2. 添加对比维度
        contextBuilder.append("【对比维度】\n");
        for (String dim : dimensions) {
            contextBuilder.append("- ").append(dim).append("\n");
        }
        contextBuilder.append("\n");
        
        // 3. 添加论文信息（基础信息 + 关键内容片段）
        contextBuilder.append("【待对比论文】\n");
        for (int i = 0; i < documents.size(); i++) {
            DocumentDO doc = documents.get(i);
            List<DocumentChunkDO> chunks = paperChunksMap.get(doc.getId());
            
            contextBuilder.append("\n论文").append(i + 1).append(":\n");
            contextBuilder.append("ID: ").append(doc.getId()).append("\n");
            contextBuilder.append("标题: ").append(doc.getTitle()).append("\n");
            
            // 只提取前3个chunk作为关键内容（通常包含摘要和引言）
            if (chunks != null && !chunks.isEmpty()) {
                contextBuilder.append("关键内容:\n");
                for (int j = 0; j < Math.min(chunks.size(), 3); j++) {
                    String content = chunks.get(j).getContent();
                    // 限制每个chunk的长度，避免prompt过长
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    contextBuilder.append(content).append("\n");
                }
            }
        }
        
        // 4. 添加明确的任务指令（放在最后，确保LLM记住任务）
        contextBuilder.append("\n\n【任务】\n");
        contextBuilder.append("基于上述").append(documents.size()).append("篇论文的内容，");
        contextBuilder.append("按照给定的").append(dimensions.size()).append("个对比维度进行详细分析对比，");
        contextBuilder.append("严格按照输出格式返回完整的JSON结构。\n");
        contextBuilder.append("注意: dimensions、papers、matrix 三个数组都必须包含实际数据，不能为空。");

        log.info("论文对比 prompt 长度: {} 字符, 论文数: {}, 维度数: {}", 
                contextBuilder.length(), documents.size(), dimensions.size());

        // 构建引用列表（使用从数据库直接获取的chunks）
        List<Document> allRetrievedDocs = new ArrayList<>();
        for (Map.Entry<Long, List<DocumentChunkDO>> entry : paperChunksMap.entrySet()) {
            Long docId = entry.getKey();
            List<DocumentChunkDO> chunks = entry.getValue();
            String docTitle = documents.stream()
                    .filter(d -> d.getId().equals(docId))
                    .findFirst()
                    .map(DocumentDO::getTitle)
                    .orElse("未知文档");
            
            for (DocumentChunkDO chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("documentId", String.valueOf(docId));
                metadata.put("chunkIndex", chunk.getChunkIndex());
                metadata.put("title", docTitle);
                
                Document doc = Document.builder()
                        .id(chunk.getVectorId())
                        .score(1.0)
                        .text(chunk.getContent())
                        .metadata(metadata)
                        .build();
                allRetrievedDocs.add(doc);
            }
        }

        log.info("论文对比准备完成: 论文数={}, 总片段数={}", documents.size(), allRetrievedDocs.size());

        try {
            // 优化：不使用 QuestionAnswerAdvisor，直接传递内容给 LLM
            // 这样避免重复检索，大幅提升性能
            String systemMessage = String.format(
                "你是学术论文对比专家。当前需要对比 %d 篇论文的 %d 个维度。" +
                "必须返回包含 dimensions、papers、matrix 三个数组的完整JSON，每个数组都必须有实际内容。" +
                "dimensions 数组包含 %d 个维度对象，papers 数组包含 %d 篇论文信息，" +
                "matrix 数组包含 %d 行(每个维度一行)，每行的 values 数组包含 %d 个值(对应每篇论文)。",
                documents.size(), dimensions.size(),
                dimensions.size(), documents.size(),
                dimensions.size(), documents.size()
            );
            
            // 调用 LLM 进行对比分析
            log.info("开始调用 LLM 进行论文对比...");
            long startTime = System.currentTimeMillis();
            
            String rawResponse = ragChatClient.prompt()
                    .system(systemMessage)
                    .user(contextBuilder.toString())
                    .call()
                    .content();
            
            long llmTime = System.currentTimeMillis() - startTime;
            log.info("LLM 响应完成,耗时: {}ms, 响应长度: {} 字符", llmTime, rawResponse.length());
            log.debug("LLM 原始响应(前500字符): {}", 
                    rawResponse.length() > 500 ? rawResponse.substring(0, 500) : rawResponse);
            
            // 清理 JSON 响应
            String cleanedJson = cleanJsonResponse(rawResponse);
            log.debug("清理后 JSON(前500字符): {}", 
                    cleanedJson.length() > 500 ? cleanedJson.substring(0, 500) : cleanedJson);
            
            // 解析为对象
            PaperComparisonVO comparison;
            try {
                comparison = objectMapper.readValue(cleanedJson, PaperComparisonVO.class);
            } catch (Exception parseEx) {
                log.error("JSON 解析失败,原始响应: {}", rawResponse, parseEx);
                throw new BusinessException("论文对比结果解析失败");
            }
            
            // 验证返回结果的完整性
            if (comparison == null || 
                comparison.getDimensions() == null || comparison.getDimensions().isEmpty() ||
                comparison.getPapers() == null || comparison.getPapers().isEmpty() ||
                comparison.getMatrix() == null || comparison.getMatrix().isEmpty()) {
                
                log.warn("LLM 返回了空结果! dimensions={}, papers={}, matrix={}", 
                        comparison != null ? comparison.getDimensions().size() : 0,
                        comparison != null ? comparison.getPapers().size() : 0,
                        comparison != null ? comparison.getMatrix().size() : 0);
                log.warn("完整的 LLM 响应: {}", rawResponse);
                
                throw new BusinessException("LLM 返回了不完整的对比结果,请重试或减少对比维度");
            }
            
            log.info("论文对比解析成功: dimensions={}, papers={}, matrix={}", 
                    comparison.getDimensions().size(), 
                    comparison.getPapers().size(),
                    comparison.getMatrix().size());

            // 构建引用
            List<CitationVO> citations = buildCitations(allRetrievedDocs, "论文对比", 200);

            log.info("论文对比完成: documentIds={}, citations={}, 总耗时: {}ms", 
                    documentIds, citations.size(), System.currentTimeMillis() - startTime);

            return RagAnswerVO.<PaperComparisonVO>builder()
                    .answer(comparison)
                    .citations(citations)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("论文对比失败: documentIds={}", documentIds, e);
            throw new BusinessException("论文对比失败: " + e.getMessage());
        }
    }

    /**
     * PA-03: 抽取论文元数据（带Redis缓存）
     */
    @Override
    public PaperMetadataVO extractPaperMetadata(Long documentId) {
        log.info("抽取论文元数据: documentId={}", documentId);

        // 验证文档权限
        Long userId = StpUtil.getLoginIdAsLong();
        validateDocumentAccess(documentId, userId);

        // 【Redis缓存】先尝试从缓存读取
        String cacheKey = PAPER_METADATA_CACHE_PREFIX + documentId;
        try {
            Object cachedObj = redisTemplate.opsForValue().get(cacheKey);
            if (cachedObj != null) {
                // Redis存储的是JSON字符串，需要反序列化
                String cachedJson = cachedObj.toString();
                PaperMetadataVO cached = objectMapper.readValue(cachedJson, PaperMetadataVO.class);
                if (cached != null && cached.getTitle() != null && !cached.getTitle().isBlank()) {
                    log.info("使用Redis缓存的元数据: documentId={}, title={}", documentId, cached.getTitle());
                    cached.setDocumentId(documentId); // 确保ID正确
                    return cached;
                }
            }
        } catch (Exception e) {
            log.warn("读取Redis缓存失败，将重新提取: documentId={}", documentId, e);
        }

        DocumentDO document = documentMapper.selectById(documentId);

        // 策略改进：直接获取文档的前10个chunk（元数据通常在论文开头）
        // 避免语义搜索匹配度低的问题
        List<DocumentChunkDO> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkDO>()
                        .eq(DocumentChunkDO::getDocumentId, documentId)
                        .eq(DocumentChunkDO::getIsDeleted, 0)
                        .orderByAsc(DocumentChunkDO::getChunkIndex)
                        .last("LIMIT 20") // 元数据通常在前10个chunk内
        );

        log.debug("元数据抽取获取前10个chunk: documentId={}, actualCount={}", documentId, chunks.size());

        if (chunks.isEmpty()) {
            log.warn("文档没有chunk数据，无法提取元数据: documentId={}", documentId);
            // 返回基本信息
            PaperMetadataVO fallback = new PaperMetadataVO();
            fallback.setDocumentId(documentId);
            fallback.setTitle(document.getTitle());
            return fallback;
        }

        // 构建包含文档开头内容的上下文
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("文档标题: ").append(document.getTitle()).append("\n\n");
        contextBuilder.append("文档开头内容:\n");
        contextBuilder.append("=".repeat(50)).append("\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkDO chunk = chunks.get(i);
            contextBuilder.append("【第 ").append(i + 1).append(" 段】\n");
            contextBuilder.append(chunk.getContent()).append("\n\n");
        }

        try {
            String prompt = paperMetadataExtractionPromptTemplate
                    .replace("{context}", contextBuilder.toString())
                    .replace("{documentId}", String.valueOf(documentId));

            PaperMetadataVO metadata = ragChatClient.prompt()
                    .system("你是一位专业的学术文献分析专家，擅长从论文中准确抽取题录信息。请仔细阅读提供的文档内容，识别论文的标题、作者、出版信息等元数据。")
                    .user(prompt)
                    .call()
                    .entity(PaperMetadataVO.class);

            // 确保 documentId 正确设置
            if (metadata != null) {
                metadata.setDocumentId(documentId);
                if (metadata.getTitle() == null || metadata.getTitle().isBlank()) {
                    metadata.setTitle(document.getTitle());
                }
                log.info("论文元数据抽取完成: documentId={}, title={}", documentId, metadata.getTitle());

                // 【Redis缓存】将提取的元数据保存到缓存（30天过期）
                // 注意：需要序列化为JSON字符串，因为RedisTemplate使用StringRedisSerializer
                try {
                    String metadataJson = objectMapper.writeValueAsString(metadata);
                    redisTemplate.opsForValue().set(
                            cacheKey,
                            metadataJson,
                            CACHE_EXPIRE_DAYS,
                            java.util.concurrent.TimeUnit.DAYS
                    );
                    log.debug("元数据已缓存到Redis: documentId={}, ttl={}天", documentId, CACHE_EXPIRE_DAYS);
                } catch (Exception cacheEx) {
                    log.warn("缓存元数据到Redis失败（不影响主流程）: documentId={}", documentId, cacheEx);
                }
            } else {
                log.warn("元数据抽取结果为空: documentId={}", documentId);
            }

            return metadata;

        } catch (Exception e) {
            log.error("论文元数据抽取失败: documentId={}", documentId, e);
            throw new BusinessException("元数据抽取失败,请稍后重试");
        }
    }

    /**
     * 清除论文元数据缓存（用于强制重新提取）
     */
    public void clearPaperMetadataCache(Long documentId) {
        String cacheKey = PAPER_METADATA_CACHE_PREFIX + documentId;
        try {
            Boolean deleted = redisTemplate.delete(cacheKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("已清除元数据缓存: documentId={}", documentId);
            } else {
                log.debug("缓存不存在或已过期: documentId={}", documentId);
            }
        } catch (Exception e) {
            log.error("清除元数据缓存失败: documentId={}", documentId, e);
        }
    }

    /**
     * PA-02: 创新点聚合
     */
    @Override
    public List<InnovationClusterVO> aggregateInnovations(List<Long> documentIds) {
        log.info("创新点聚合: documentIds={}", documentIds);

        Long userId = StpUtil.getLoginIdAsLong();

        // 验证所有文档权限
        for (Long docId : documentIds) {
            validateDocumentAccess(docId, userId);
        }

        // 为每篇论文提取创新点
        List<InnovationClusterVO.InnovationPoint> allInnovations = documentIds.stream()
                .flatMap(docId -> {
                    try {
                        return extractInnovations(docId).stream();
                    } catch (Exception e) {
                        log.error("提取文档创新点失败: documentId={}", docId, e);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();

        if (allInnovations.isEmpty()) {
            return List.of();
        }

        // 使用LLM对创新点进行聚类
        try {
            String innovationList = allInnovations.stream()
                    .map(inn -> String.format("- %s (来自: %s, documentId: %d, noveltyScore: %.2f)",
                            inn.getDescription(),
                            inn.getPaperTitle(),
                            inn.getDocumentId(),
                            inn.getNoveltyScore()))
                    .collect(Collectors.joining("\n"));
            
            String prompt = innovationClusteringPromptTemplate.replace("{innovationList}", innovationList);

            String response = ragChatClient.prompt()
                    .system("你是一位科研创新分析专家，擅长识别研究主题和创新趋势。")
                    .user(prompt)
                    .call()
                    .content();

            // 解析JSON响应
            List<InnovationClusterVO> clusters = parseInnovationClusters(response);

            log.info("创新点聚合完成: 聚类数={}", clusters.size());
            return clusters;

        } catch (Exception e) {
            log.error("创新点聚合失败", e);
            throw new BusinessException("创新点聚合失败,请稍后重试");
        }
    }

    /**
     * 提取单篇论文的创新点
     * 策略：直接获取论文前N个chunk（按位置顺序），确保覆盖摘要、引言、方法等核心部分
     */
    private List<InnovationClusterVO.InnovationPoint> extractInnovations(Long documentId) {
        DocumentDO document = documentMapper.selectById(documentId);

        // 策略改进：直接从数据库查询文档的前20个chunk（按chunkIndex排序）
        // 论文的核心内容（摘要、引言、方法、结论）通常集中在文档前部
        // 这样避免了语义搜索匹配度低的问题
        List<DocumentChunkDO> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkDO>()
                        .eq(DocumentChunkDO::getDocumentId, documentId)
                        .eq(DocumentChunkDO::getIsDeleted, 0)
                        .orderByAsc(DocumentChunkDO::getChunkIndex)
        );


        if (chunks.isEmpty()) {
            log.warn("文档没有chunk数据，无法提取创新点: documentId={}", documentId);
            return List.of();
        }

        // 将数据库chunk转换为Document对象（便于后续处理）
        List<Document> retrievedDocs = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("documentId", documentId);
                    metadata.put("chunkIndex", chunk.getChunkIndex());
                    metadata.put("title", document.getTitle());
                    return new Document(chunk.getId().toString(), chunk.getContent(), metadata);
                })
                .collect(Collectors.toList());

        // 构建包含论文核心内容的详细prompt
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("论文标题: ").append(document.getTitle()).append("\n\n");
        contextBuilder.append("论文核心内容片段:\n");
        contextBuilder.append("=".repeat(50)).append("\n\n");

        for (int i = 0; i < Math.min(retrievedDocs.size(), 10); i++) { // 最多取10个片段
            Document doc = retrievedDocs.get(i);
            contextBuilder.append("【片段 ").append(i + 1).append("】\n");
            contextBuilder.append(doc.getText()).append("\n\n");
        }

        String prompt = innovationExtractionPromptTemplate.replace("{context}", contextBuilder.toString());

        try {
            String response = ragChatClient.prompt()
                    .system("你是一位资深的科研论文审稿专家，具有丰富的学术评审经验。你擅长快速识别论文的核心贡献和创新价值，能够准确区分真正的创新点和常规工作。请基于提供的论文内容进行深度分析，提取真实、具体的创新点。")
                    .user(prompt)
                    .call()
                    .content();

            log.debug("创新点提取响应: documentId={}, response={}", documentId,
                    response != null ? response.substring(0, Math.min(300, response.length())) : "null");

            // 解析并补充论文信息
            List<InnovationClusterVO.InnovationPoint> innovations = parseInnovationPoints(response);

            if (innovations.isEmpty()) {
                log.warn("LLM未能提取创新点: documentId={}, title={}", documentId, document.getTitle());
            }

            innovations.forEach(inn -> {
                inn.setPaperTitle(document.getTitle());
                inn.setDocumentId(documentId);
            });

            log.info("成功提取 {} 个创新点: documentId={}", innovations.size(), documentId);
            return innovations;

        } catch (Exception e) {
            log.error("提取创新点失败: documentId={}", documentId, e);
            return List.of();
        }
    }

    /**
     * 清理JSON响应内容
     * 移除markdown标记、多余的空白字符等
     */
    private String cleanJsonResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return rawResponse;
        }

        String cleaned = rawResponse.trim();

        // 1. 移除markdown代码块标记
        cleaned = cleaned.replaceAll("```json\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*", "");

        // 2. 查找JSON对象的开始和结束位置
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }

        // 3. 移除BOM字符(如果存在)
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }

        // 4. 统一换行符
        cleaned = cleaned.replaceAll("\\r\\n", "\\n");

        return cleaned.trim();
    }


    /**
     * 解析创新点列表
     */
    private List<InnovationClusterVO.InnovationPoint> parseInnovationPoints(String json) {
        try {
            // 提取JSON部分
            String cleanJson = extractJsonArray(json);

            return objectMapper.readValue(cleanJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            InnovationClusterVO.InnovationPoint.class));

        } catch (Exception e) {
            log.warn("解析创新点失败: {}", json, e);
            return List.of();
        }
    }

    /**
     * 解析创新点聚类结果
     */
    private List<InnovationClusterVO> parseInnovationClusters(String json) {
        try {
            String cleanJson = extractJsonArray(json);

            return objectMapper.readValue(cleanJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            InnovationClusterVO.class));

        } catch (Exception e) {
            log.warn("解析创新聚类失败: {}", json, e);
            return List.of();
        }
    }

    /**
     * 从响应中提取JSON数组
     */
    private String extractJsonArray(String text) {
        // 移除 Markdown 代码块标记
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");

        // 查找 JSON 数组
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');

        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned.trim();
    }
}
