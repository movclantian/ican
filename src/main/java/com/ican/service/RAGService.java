package com.ican.service;

import com.ican.model.vo.InnovationClusterVO;
import com.ican.model.vo.PaperComparisonVO;
import com.ican.model.vo.PaperMetadataVO;
import com.ican.model.vo.PaperSummaryVO;
import com.ican.model.vo.RagAnswerVO;
import com.ican.model.vo.RagChatResultVO;
import com.ican.model.vo.TeachingPlanListVO;
import com.ican.model.vo.TeachingPlanVO;

import java.util.List;

/**
 * RAG 服务接口
 * 
 * @author ICan
 * @since 2024-10-06
 */
public interface RAGService {
    
    /**
     * RAG 问答（带引用）
     * 
     * @param conversationId 会话ID
     * @param query 用户问题
     * @return AI 回答和引用
     */
    RagChatResultVO ragChat(String conversationId, String query);
    
    /**
     * 基于文档的问答（带引用）
     * 
     * @param documentId 文档ID
     * @param query 用户问题
     * @return AI 回答和引用
     */
    RagChatResultVO documentChat(Long documentId, String query);
    
    /**
     * 论文总结 - 结构化输出（带引用）
     * 
     * @param documentId 论文文档ID
     * @return 结构化总结和引用
     */
    RagAnswerVO<PaperSummaryVO> summarizePaper(Long documentId);
    
    /**
     * 生成教学设计 - 结构化输出（带引用）
     * 
     * @param topic 课题
     * @param grade 学段
     * @param subject 学科
     * @param documentIds 参考文档ID列表
     * @return 教学设计内容和引用
     */
    RagAnswerVO<TeachingPlanVO> generateTeachingPlan(String topic, String grade, String subject, List<Long> documentIds);
    
    /**
     * 保存教学设计
     * 
     * @param teachingPlanVO 教学设计内容
     * @return 教案ID
     */
    Long saveTeachingPlan(TeachingPlanVO teachingPlanVO);
    
    /**
     * 获取用户的教学设计列表
     * 
     * @param userId 用户ID
     * @return 教学设计列表
     */
    List<TeachingPlanListVO> getUserTeachingPlans(Long userId);
    
    /**
     * 获取教学设计详情
     * 
     * @param planId 教案ID
     * @return 教学设计详情
     */
    TeachingPlanVO getTeachingPlan(Long planId);
    
    /**
     * 删除教学设计
     * 
     * @param planId 教案ID
     */
    void deleteTeachingPlan(Long planId);
    
    /**
     * 论文对比 (PA-01)
     * 
     * @param documentIds 文档ID列表
     * @param dimensions 对比维度（可选）
     * @return 对比矩阵
     */
    RagAnswerVO<PaperComparisonVO> comparePapers(List<Long> documentIds, List<String> dimensions);
    
    /**
     * 抽取论文元数据 (PA-03)
     * 
     * @param documentId 文档ID
     * @return 论文元数据
     */
    PaperMetadataVO extractPaperMetadata(Long documentId);
    
    /**
     * 创新点聚合 (PA-02)
     * 
     * @param documentIds 文档ID列表
     * @return 创新点聚类结果
     */
    List<InnovationClusterVO> aggregateInnovations(List<Long> documentIds);
}

