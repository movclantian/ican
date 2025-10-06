package com.ican.service;

import com.ican.model.vo.DocumentVO;

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
}

