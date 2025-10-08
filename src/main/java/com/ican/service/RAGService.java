package com.ican.service;

import com.ican.model.vo.PaperSummaryVO;
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
     * RAG 问答
     * 
     * @param conversationId 会话ID
     * @param query 用户问题
     * @return AI 回答
     */
    String ragChat(String conversationId, String query);
    
    /**
     * 基于文档的问答
     * 
     * @param documentId 文档ID
     * @param query 用户问题
     * @return AI 回答
     */
    String documentChat(Long documentId, String query);
    
    /**
     * 论文总结 - 结构化输出
     * 
     * @param documentId 论文文档ID
     * @return 结构化总结
     */
    PaperSummaryVO summarizePaper(Long documentId);
    
    /**
     * 生成教学设计 - 结构化输出
     * 
     * @param topic 课题
     * @param grade 学段
     * @param subject 学科
     * @param documentIds 参考文档ID列表
     * @return 教学设计内容
     */
    TeachingPlanVO generateTeachingPlan(String topic, String grade, String subject, List<Long> documentIds);
    
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
}

