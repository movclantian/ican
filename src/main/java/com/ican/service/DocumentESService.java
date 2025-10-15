package com.ican.service;

import com.ican.model.entity.DocumentES;
import com.ican.model.vo.DocumentSearchResultVO;

import java.util.List;

/**
 * Elasticsearch 文档服务接口 - 增强版
 * 
 * 提供基于 Elasticsearch 的高级文档搜索功能:
 * - 全文搜索 (BM25 算法)
 * - 混合搜索 (向量相似度 + BM25)
 * - 高亮片段提取
 * 
 * @author 席崇援
 */
public interface DocumentESService {
    
    /**
     * 索引文档到 Elasticsearch
     * 
     * @param documentId 文档ID
     * @param userId 用户ID
     * @param title 标题
     * @param content 内容
     * @param type 类型
     * @param fileSize 文件大小
     * @param status 状态
     */
    void indexDocument(Long documentId, Long userId, String title, String content, 
                      String type, Long fileSize, String status);
    
    /**
     * 更新文档状态
     * 
     * @param documentId 文档ID
     * @param status 状态
     */
    void updateDocumentStatus(Long documentId, String status);
    
    /**
     * 全文搜索文档 - 带高亮和关键词提取
     * 
     * <p>功能特性:</p>
     * <ul>
     *   <li>使用 Elasticsearch BM25 算法评分</li>
     *   <li>在 title 和 content 字段中搜索</li>
     *   <li>提取高亮片段(不含HTML标签)</li>
     *   <li>返回关键词列表供前端高亮</li>
     * </ul>
     * 
     * @param userId 用户ID(安全过滤)
     * @param query 搜索查询
     * @param topK 返回数量
     * @return 搜索结果列表(含高亮片段和关键词)
     */
    List<DocumentSearchResultVO> fullTextSearchWithHighlight(Long userId, String query, int topK);
    
    /**
     * 删除文档
     * 
     * @param documentId 文档ID
     */
    void deleteDocument(Long documentId);
    
    /**
     * 获取用户的所有文档
     * 
     * @param userId 用户ID
     * @return 文档列表
     */
    List<DocumentES> getUserDocuments(Long userId);
}
