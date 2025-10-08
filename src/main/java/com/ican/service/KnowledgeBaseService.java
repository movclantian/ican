package com.ican.service;

import com.ican.model.dto.CreateKBDTO;
import com.ican.model.entity.KnowledgeBaseDO;
import com.ican.model.vo.TagVO;

import java.util.List;

/**
 * 知识库服务接口
 * 
 * @author ican
 */
public interface KnowledgeBaseService {
    
    /**
     * 创建知识库
     * 
     * @param createKBDTO 创建知识库DTO
     * @return 知识库ID
     */
    Long createKnowledgeBase(CreateKBDTO createKBDTO);
    
    /**
     * 获取用户的知识库列表
     * 
     * @param userId 用户ID
     * @return 知识库列表
     */
    List<KnowledgeBaseDO> getUserKnowledgeBases(Long userId);
    
    /**
     * 获取知识库详情
     * 
     * @param kbId 知识库ID
     * @return 知识库详情
     */
    KnowledgeBaseDO getKnowledgeBase(Long kbId);
    
    /**
     * 更新知识库
     * 
     * @param kbId 知识库ID
     * @param createKBDTO 更新内容
     */
    void updateKnowledgeBase(Long kbId, CreateKBDTO createKBDTO);
    
    /**
     * 删除知识库
     * 
     * @param kbId 知识库ID
     */
    void deleteKnowledgeBase(Long kbId);
    
    /**
     * 将文档归档到知识库
     * 
     * @param documentId 文档ID
     * @param kbId 知识库ID
     */
    void archiveDocument(Long documentId, Long kbId);
    
    /**
     * 创建标签
     * 
     * @param name 标签名称
     * @param color 标签颜色
     * @return 标签ID
     */
    Long createTag(String name, String color);
    
    /**
     * 获取用户的标签列表
     * 
     * @param userId 用户ID
     * @return 标签列表
     */
    List<TagVO> getUserTags(Long userId);
    
    /**
     * 为文档添加标签
     * 
     * @param documentId 文档ID
     * @param tagIds 标签ID列表
     */
    void tagDocument(Long documentId, List<Long> tagIds);
    
    /**
     * 根据标签获取文档列表
     * 
     * @param tagId 标签ID
     * @param userId 用户ID
     * @return 文档ID列表
     */
    List<Long> getDocumentsByTag(Long tagId, Long userId);
}
