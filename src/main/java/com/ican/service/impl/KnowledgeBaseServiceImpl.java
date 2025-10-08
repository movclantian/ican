package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.mapper.*;
import com.ican.model.dto.CreateKBDTO;
import com.ican.model.entity.*;
import com.ican.model.vo.TagVO;
import com.ican.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库服务实现
 * 
 * @author ican
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final TagMapper tagMapper;
    private final DocumentTagMapper documentTagMapper;
    private final DocumentMapper documentMapper;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createKnowledgeBase(CreateKBDTO createKBDTO) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
            .name(createKBDTO.getName())
            .description(createKBDTO.getDescription())
            .userId(userId)
            .documentCount(0)
            .isDeleted(0)
            .createTime(LocalDateTime.now())
            .updateTime(LocalDateTime.now())
            .build();
        
        knowledgeBaseMapper.insert(kb);
        log.info("创建知识库成功: id={}, name={}, userId={}", kb.getId(), kb.getName(), userId);
        
        return kb.getId();
    }
    
    @Override
    public List<KnowledgeBaseDO> getUserKnowledgeBases(Long userId) {
        return knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getUserId, userId)
                .eq(KnowledgeBaseDO::getIsDeleted, 0)
                .orderByDesc(KnowledgeBaseDO::getUpdateTime)
        );
    }
    
    @Override
    public KnowledgeBaseDO getKnowledgeBase(Long kbId) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getIsDeleted() == 1) {
            throw new BusinessException("知识库不存在");
        }
        
        // 检查所有权
        if (!kb.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该知识库");
        }
        
        return kb;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateKnowledgeBase(Long kbId, CreateKBDTO createKBDTO) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        
        // 检查所有权
        if (!kb.getUserId().equals(userId)) {
            throw new BusinessException("无权修改该知识库");
        }
        
        kb.setName(createKBDTO.getName());
        kb.setDescription(createKBDTO.getDescription());
        kb.setUpdateTime(LocalDateTime.now());
        
        knowledgeBaseMapper.updateById(kb);
        log.info("更新知识库成功: id={}", kbId);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long kbId) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        
        if (!kb.getUserId().equals(userId)) {
            throw new BusinessException("只有所有者可以删除知识库");
        }
        
        kb.setIsDeleted(1);
        kb.setUpdateTime(LocalDateTime.now());
        knowledgeBaseMapper.updateById(kb);
        
        log.info("删除知识库成功: id={}", kbId);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveDocument(Long documentId, Long kbId) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 验证文档所有权
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("无权归档该文档");
        }
        
        // 验证知识库所有权
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || !kb.getUserId().equals(userId)) {
            throw new BusinessException("无权向该知识库添加文档");
        }
        
        // 关联文档到知识库
        document.setKbId(kbId);
        documentMapper.updateById(document);
        
        // 更新知识库文档数量
        kb.setDocumentCount(kb.getDocumentCount() + 1);
        kb.setUpdateTime(LocalDateTime.now());
        knowledgeBaseMapper.updateById(kb);
        
        log.info("文档归档成功: documentId={}, kbId={}", documentId, kbId);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTag(String name, String color) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 检查标签是否已存在
        TagDO existingTag = tagMapper.selectOne(
            new LambdaQueryWrapper<TagDO>()
                .eq(TagDO::getName, name)
                .eq(TagDO::getUserId, userId)
        );
        
        if (existingTag != null) {
            return existingTag.getId();
        }
        
        TagDO tag = TagDO.builder()
            .name(name)
            .color(color)
            .userId(userId)
            .createTime(LocalDateTime.now())
            .build();
        
        tagMapper.insert(tag);
        log.info("创建标签成功: id={}, name={}", tag.getId(), name);
        
        return tag.getId();
    }
    
    @Override
    public List<TagVO> getUserTags(Long userId) {
        List<TagDO> tags = tagMapper.selectList(
            new LambdaQueryWrapper<TagDO>()
                .eq(TagDO::getUserId, userId)
                .orderByDesc(TagDO::getCreateTime)
        );
        
        return tags.stream()
            .map(tag -> {
                // 统计使用次数
                int usageCount = documentTagMapper.selectCount(
                    new LambdaQueryWrapper<DocumentTagDO>()
                        .eq(DocumentTagDO::getTagId, tag.getId())
                ).intValue();
                
                return TagVO.builder()
                    .id(tag.getId())
                    .name(tag.getName())
                    .color(tag.getColor())
                    .usageCount(usageCount)
                    .createTime(tag.getCreateTime())
                    .build();
            })
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tagDocument(Long documentId, List<Long> tagIds) {
        Long userId = StpUtil.getLoginIdAsLong();
        
        // 验证文档权限
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null || !document.getUserId().equals(userId)) {
            throw new BusinessException("无权标记该文档");
        }
        
        // 删除旧标签关联
        documentTagMapper.delete(
            new LambdaQueryWrapper<DocumentTagDO>()
                .eq(DocumentTagDO::getDocumentId, documentId)
        );
        
        // 添加新标签关联
        for (Long tagId : tagIds) {
            DocumentTagDO documentTag = DocumentTagDO.builder()
                .documentId(documentId)
                .tagId(tagId)
                .createTime(LocalDateTime.now())
                .build();
            documentTagMapper.insert(documentTag);
        }
        
        log.info("文档标签更新成功: documentId={}, tagCount={}", documentId, tagIds.size());
    }
    
    @Override
    public List<Long> getDocumentsByTag(Long tagId, Long userId) {
        List<DocumentTagDO> documentTags = documentTagMapper.selectList(
            new LambdaQueryWrapper<DocumentTagDO>()
                .eq(DocumentTagDO::getTagId, tagId)
        );
        
        List<Long> documentIds = documentTags.stream()
            .map(DocumentTagDO::getDocumentId)
            .toList();
        
        if (documentIds.isEmpty()) {
            return List.of();
        }
        
        // 只返回用户有权访问的文档
        List<DocumentDO> documents = documentMapper.selectList(
            new LambdaQueryWrapper<DocumentDO>()
                .in(DocumentDO::getId, documentIds)
                .eq(DocumentDO::getUserId, userId)
        );
        
        return documents.stream()
            .map(DocumentDO::getId)
            .collect(Collectors.toList());
    }
}
