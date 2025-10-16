package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base62;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ican.mapper.DocumentChunkMapper;
import com.ican.mapper.TeachingPlanMapper;
import com.ican.model.entity.DocumentChunkDO;
import com.ican.model.entity.TeachingPlanDO;
import com.ican.model.vo.CitationVO;
import com.ican.model.vo.RagAnswerVO;
import com.ican.model.vo.TeachingPlanListVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.TeachingPlanService;
import com.ican.utils.NumberConversionUtils;
import com.ican.utils.StructuredOutputUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;
import com.ican.config.RAGConfig;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 教学设计服务实现类
 *
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeachingPlanServiceImpl implements TeachingPlanService {

    private final TeachingPlanMapper teachingPlanMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ChatClient ragChatClient;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RAGConfig ragConfig;

    // 注入提示词模板
    @Qualifier("teachingPlanPromptTemplate")
    private final String teachingPlanPromptTemplate;
    @Qualifier("keywordExtractionPromptTemplate")
    private final String keywordExtractionPromptTemplate;

    @Override
    public RagAnswerVO<TeachingPlanVO> generateTeachingPlan(String topic, String grade, String subject,
                                                            List<Long> documentIds) {
        log.info("生成教学设计: topic={}, grade={}, subject={}, documentIds={}", topic, grade, subject, documentIds);

        Long userId = StpUtil.getLoginIdAsLong();

        // 构建过滤表达式
        Filter.Expression filterExpression;
        if (documentIds != null && !documentIds.isEmpty()) {
            String[] encodedDocIds = documentIds.stream()
                    .map(id -> Base62.encode(String.valueOf(id)))
                    .toArray(String[]::new);
            filterExpression = new FilterExpressionBuilder()
                    .and(
                            new FilterExpressionBuilder().eq("userId", Base62.encode(String.valueOf(userId))),
                            new FilterExpressionBuilder().in("documentId", (Object[]) encodedDocIds))
                    .build();
        } else {
            filterExpression = new FilterExpressionBuilder().eq("userId",
                    Base62.encode(String.valueOf(userId))).build();
        }

        // 提取核心关键词
        List<String> searchQueries = extractSearchQueries(topic);
        log.info("检索优化: 原始topic='{}', 提取查询词={}", topic, searchQueries);

        // 多查询检索
        List<Document> retrievedDocs = performMultiQuerySearch(searchQueries, filterExpression, 10, 0.5);
        log.info("多查询检索完成: 共检索到 {} 个文档片段", retrievedDocs.size());

        // 构建基础SearchRequest
        SearchRequest baseRequest = SearchRequest.builder()
                .query(topic)
                .similarityThreshold(0.5)
                .topK(ragConfig.getRetrieval().getTopK())
                .filterExpression(filterExpression)
                .build();

        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(baseRequest)
                .build();

        try {
            String userPrompt = teachingPlanPromptTemplate
                    .replace("{topic}", topic)
                    .replace("{grade}", grade)
                    .replace("{subject}", subject);

            log.info("教学设计生成 - 开始调用LLM");
            String rawResponse = ragChatClient.prompt()
                    .advisors(qaAdvisor)
                    .system("你是一位经验丰富的教学设计专家。请根据提供的参考资料和要求，生成完整详细的教学设计方案。必须严格按照指定的JSON格式返回，所有字段都必须填写完整，不要使用占位符或空数组。")
                    .user(userPrompt)
                    .call()
                    .content();

            log.info("LLM响应接收完成");
            TeachingPlanVO teachingPlan;
            try {
                teachingPlan = objectMapper.readValue(rawResponse, TeachingPlanVO.class);
            } catch (Exception parseEx) {
                log.error("JSON解析失败: {}", rawResponse, parseEx);
                throw new BusinessException("解析教学设计失败: " + parseEx.getMessage());
            }

            teachingPlan.validate();

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

    @Override
    public Long saveTeachingPlan(TeachingPlanVO teachingPlanVO) {
        Long userId = StpUtil.getLoginIdAsLong();

        try {
            TeachingPlanDO teachingPlan = new TeachingPlanDO();
            teachingPlan.setUserId(userId);
            teachingPlan.setTitle(teachingPlanVO.getTitle());
            teachingPlan.setGrade(teachingPlanVO.getGrade());
            teachingPlan.setSubject(teachingPlanVO.getSubject());

            String contentJson = objectMapper.writeValueAsString(teachingPlanVO);
            teachingPlan.setContent(contentJson);

            teachingPlanMapper.insert(teachingPlan);
            log.info("教学设计保存成功: id={}, userId={}, title={}",
                    teachingPlan.getId(), userId, teachingPlanVO.getTitle());
            return teachingPlan.getId();
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON序列化失败: userId={}", userId, e);
            throw new BusinessException("教学设计保存失败");
        }
    }

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

    @Override
    public TeachingPlanVO getTeachingPlan(Long planId) {
        Long userId = StpUtil.getLoginIdAsLong();

        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }

        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该教学设计");
        }

        try {
            TeachingPlanVO vo = objectMapper.readValue(teachingPlan.getContent(), TeachingPlanVO.class);
            return vo;
        } catch (JsonProcessingException e) {
            log.error("教学设计JSON反序列化失败: planId={}", planId, e);
            throw new BusinessException("教学设计数据解析失败");
        }
    }

    @Override
    public void deleteTeachingPlan(Long planId) {
        Long userId = StpUtil.getLoginIdAsLong();

        TeachingPlanDO teachingPlan = teachingPlanMapper.selectById(planId);
        if (teachingPlan == null || teachingPlan.getIsDeleted() == 1) {
            throw new BusinessException("教学设计不存在");
        }

        if (!teachingPlan.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该教学设计");
        }

        teachingPlan.setIsDeleted(1);
        teachingPlanMapper.updateById(teachingPlan);
        log.info("教学设计删除成功: planId={}, userId={}", planId, userId);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 提取搜索关键词
     */
    private List<String> extractSearchQueries(String topic) {
        List<String> queries = new ArrayList<>();
        queries.add(topic);

        try {
            String prompt = keywordExtractionPromptTemplate.replace("{topic}", topic);

            // 使用 StructuredOutputUtils 获取结构化字符串列表
            List<String> keywords = StructuredOutputUtils.getStringList(
                    ragChatClient,
                    "你是一个专业的信息检索专家,擅长快速提取核心关键词。",
                    prompt
            );

            if (keywords != null && !keywords.isEmpty()) {
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isBlank() && !keyword.equals(topic)) {
                        queries.add(keyword.trim());
                    }
                }
                log.debug("LLM提取关键词: {} -> {}", topic, keywords);
                return queries;
            }

            log.debug("LLM未返回关键词，降级到启发式提取");
            List<String> heuristicKeywords = extractKeywordsByHeuristic(topic);
            if (!heuristicKeywords.isEmpty()) {
                queries.addAll(heuristicKeywords);
                log.debug("启发式提取关键词: {} -> {}", topic, heuristicKeywords);
            }

            return queries;

        } catch (Exception e) {
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
     */
    private List<String> extractKeywordsByHeuristic(String topic) {
        List<String> keywords = new ArrayList<>();

        Set<String> stopWords = Set.of(
                "的", "了", "在", "是", "有", "和", "与", "及", "或", "等",
                "研究", "分析", "探讨", "讨论", "论题", "课题", "主题",
                "教学", "设计", "方案", "计划", "内容",
                "应用", "实践", "案例", "方法", "技术"
        );

        String[] tokens = topic.split("[\\s，。、；：\\(\\)\\[\\]《》]+");

        for (String token : tokens) {
            token = token.trim();
            if (token.length() >= 2 && !stopWords.contains(token)) {
                keywords.add(token);
            }
        }

        Pattern pattern = Pattern.compile("[a-zA-Z]{3,}");
        Matcher matcher = pattern.matcher(topic);
        while (matcher.find()) {
            String englishWord = matcher.group();
            if (!keywords.contains(englishWord)) {
                keywords.add(englishWord);
            }
        }

        return keywords.stream()
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
    }

    /**
     * 多查询并行检索
     */
    private List<Document> performMultiQuerySearch(List<String> queries, Filter.Expression filterExpression,
                                                   int topK, double threshold) {
        long startTime = System.currentTimeMillis();

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
                .collect(Collectors.toMap(
                        Document::getId,
                        doc -> doc,
                        (existing, replacement) -> {
                            Double existingScore = existing.getScore();
                            Double replacementScore = replacement.getScore();

                            if (replacementScore != null &&
                                    (existingScore == null || replacementScore > existingScore)) {
                                return replacement;
                            }
                            return existing;
                        },
                        java.util.concurrent.ConcurrentHashMap::new
                ));

        List<Document> result = new ArrayList<>(docMap.values());
        result.sort((d1, d2) -> {
            Double score1 = d1.getScore();
            Double score2 = d2.getScore();
            if (score1 == null && score2 == null) return 0;
            if (score1 == null) return 1;
            if (score2 == null) return -1;
            return Double.compare(score2, score1);
        });

        if (result.size() > topK) {
            result = result.subList(0, topK);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.debug("并行多查询检索完成: {} 个查询词, 耗时 {} ms, 返回 {} 个结果",
                queries.size(), duration, result.size());

        return result;
    }

    /**
     * 构建引用列表 - 前端高亮方案
     */
    private List<CitationVO> buildCitations(List<Document> docs, String query, int maxLen) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 提取关键词列表(前端用于高亮)
        List<String> keywords = query == null || query.isBlank() 
            ? List.of() 
            : Arrays.stream(query.trim().split("\\s+"))
                .filter(kw -> !kw.isBlank())
                .toList();
        
        return docs.stream().map(d -> {
            Long documentId = NumberConversionUtils.toLong(String.valueOf(d.getMetadata().get("documentId")));
            Integer chunkIndex = NumberConversionUtils.toInteger(String.valueOf(d.getMetadata().get("chunkIndex")));
            Double score = d.getScore();
            String title = d.getMetadata().getOrDefault("title", "未知文档").toString();
            String text = d.getText();
            if (text != null && text.length() > maxLen) {
                text = text.substring(0, maxLen) + "...";
            }

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
                        position = "第 " + (chunkIndex + 1) + " 段";
                    }
                }

                if (!d.getMetadata().isEmpty()) {
                    try {
                        metadataJson = objectMapper.writeValueAsString(d.getMetadata());
                    } catch (Exception ignored) {
                        metadataJson = d.getMetadata().toString();
                    }
                }
            } catch (Exception e) {
                log.trace("查询chunkId失败", e);
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

}
