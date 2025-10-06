package com.ican.service;

import com.ican.model.vo.DocumentVO;
import org.springframework.ai.document.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档服务接口
 * 
 * @author ICan
 * @since 2024-10-06
 */
public interface DocumentService {
    
    /**
     * 上传并处理文档
     * 
     * @param file 文件
     * @param type 文档类型
     * @param userId 用户ID
     * @return 文档ID
     */
    Long uploadDocument(MultipartFile file, String type, Long userId);
    
    /**
     * 获取用户的文档列表
     * 
     * @param userId 用户ID
     * @param type 文档类型(可选)
     * @return 文档列表
     */
    List<DocumentVO> getUserDocuments(Long userId, String type);
    
    /**
     * 获取文档详情
     * 
     * @param documentId 文档ID
     * @return 文档信息
     */
    DocumentVO getDocument(Long documentId);
    
    /**
     * 解析文档内容
     * 
     * @param documentId 文档ID
     * @return 文档内容
     */
    String parseDocument(Long documentId);
    
    /**
     * 将文档向量化并存储
     * 
     * @param documentId 文档ID
     * @param content 文档内容
     */
    void vectorizeAndStore(Long documentId, String content);
    
    /**
     * 检索相关文档
     * 
     * @param query 查询文本
     * @param topK 返回数量
     * @return 相关文档列表
     */
    List<Document> searchSimilarDocuments(String query, int topK);
    
    /**
     * 删除文档
     * 
     * @param documentId 文档ID
     */
    void deleteDocument(Long documentId);
}

