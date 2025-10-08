package com.ican.service;

import com.ican.model.vo.DocumentVO;
import com.ican.model.vo.SearchResultVO;

import java.util.List;

/**
 * 搜索服务接口
 * 
 * @author ICan
 * @since 2024-10-06
 */
public interface SearchService {
    
    /**
     * 全文搜索文档
     * 
     * @param keyword 关键词
     * @param type 文档类型(可选)
     * @param userId 用户ID
     * @return 文档列表
     */
    List<DocumentVO> searchDocuments(String keyword, String type, Long userId);
    
    /**
     * 混合搜索 - 结合向量检索和全文检索
     * 
     * @param query 查询文本
     * @param userId 用户ID
     * @return 文档列表
     */
    List<DocumentVO> hybridSearch(String query, Long userId);
    
    /**
     * 统一搜索 API (SR-01)
     * 结合向量检索和全文检索，返回带高亮的结果
     * 
     * @param query 查询文本
     * @param type 文档类型(可选)
     * @param topK 返回数量
    * @param userId 当前登录用户ID（用于数据隔离）
     * @return 搜索结果列表（带高亮片段）
     */
    List<SearchResultVO> unifiedSearch(String query, String type, Integer topK, Long userId);
    
    /**
     * 查询纠错 (SR-03)
     * 
     * @param query 原始查询
     * @return 纠错后的查询
     */
    String correctQuery(String query);
}

