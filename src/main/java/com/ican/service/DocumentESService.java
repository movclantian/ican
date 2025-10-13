package com.ican.service;

import com.ican.model.entity.DocumentES;

import java.util.List;

/**
 * Elasticsearch 文档服务接口
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
     * 全文搜索文档
     * 
     * @param userId 用户ID
     * @param keyword 关键词
     * @return 文档列表
     */
    List<DocumentES> searchDocuments(Long userId, String keyword);
    
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
