package com.ican.repository;

import com.ican.model.entity.DocumentES;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Elasticsearch 文档仓库
 * 
 * @author ICan
 * @since 2024-10-08
 */
@Repository
public interface DocumentESRepository extends ElasticsearchRepository<DocumentES, Long> {
    
    /**
     * 根据用户ID查询文档
     * 
     * @param userId 用户ID
     * @return 文档列表
     */
    List<DocumentES> findByUserId(Long userId);
    
    /**
     * 根据用户ID和标题模糊查询
     * 
     * @param userId 用户ID
     * @param title 标题关键词
     * @return 文档列表
     */
    List<DocumentES> findByUserIdAndTitleContaining(Long userId, String title);
    
    /**
     * 根据用户ID和内容模糊查询
     * 
     * @param userId 用户ID
     * @param content 内容关键词
     * @return 文档列表
     */
    List<DocumentES> findByUserIdAndContentContaining(Long userId, String content);
}
