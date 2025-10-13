package com.ican.service.impl;

import cn.hutool.core.util.StrUtil;
import com.ican.model.entity.DocumentES;
import com.ican.repository.DocumentESRepository;
import com.ican.service.DocumentESService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 文档服务实现类
 * 
 * @author 席崇援
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentESServiceImpl implements DocumentESService {
    
    private final DocumentESRepository documentESRepository;
    
    @Override
    public void indexDocument(Long documentId, Long userId, String title, String content,
                             String type, Long fileSize, String status) {
        try {
            DocumentES documentES = DocumentES.builder()
                .id(documentId)
                .userId(userId)
                .title(title)
                .content(content)
                .type(type)
                .fileSize(fileSize)
                .status(status)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
            
            documentESRepository.save(documentES);
            
            log.info("文档已索引到ES: documentId={}, title={}", documentId, title);
        } catch (Exception e) {
            log.error("索引文档到ES失败: documentId={}", documentId, e);
            throw new BusinessException("索引文档失败: " + e.getMessage());
        }
    }
    
    @Override
    public void updateDocumentStatus(Long documentId, String status) {
        try {
            documentESRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(status);
                doc.setUpdateTime(LocalDateTime.now());
                documentESRepository.save(doc);
                log.info("更新ES文档状态: documentId={}, status={}", documentId, status);
            });
        } catch (Exception e) {
            log.error("更新ES文档状态失败: documentId={}", documentId, e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    @Override
    public List<DocumentES> searchDocuments(Long userId, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return getUserDocuments(userId);
        }
        
        try {
            // 使用仓库方法进行搜索，先搜索标题和内容
            List<DocumentES> titleResults = documentESRepository.findByUserIdAndTitleContaining(userId, keyword);
            List<DocumentES> contentResults = documentESRepository.findByUserIdAndContentContaining(userId, keyword);
            
            // 合并去重（使用ID去重）
            List<DocumentES> results = new ArrayList<>(titleResults);
            contentResults.forEach(doc -> {
                boolean exists = results.stream().anyMatch(r -> r.getId().equals(doc.getId()));
                if (!exists) {
                    results.add(doc);
                }
            });
            
            log.info("ES全文搜索完成: userId={}, keyword={}, results={}", userId, keyword, results.size());
            
            return results;
        } catch (Exception e) {
            log.error("ES搜索失败: userId={}, keyword={}", userId, keyword, e);
            // 降级：返回空列表，不影响主流程
            return new ArrayList<>();
        }
    }
    
    @Override
    public void deleteDocument(Long documentId) {
        try {
            documentESRepository.deleteById(documentId);
            log.info("从ES删除文档: documentId={}", documentId);
        } catch (Exception e) {
            log.error("从ES删除文档失败: documentId={}", documentId, e);
            // 不抛出异常
        }
    }
    
    @Override
    public List<DocumentES> getUserDocuments(Long userId) {
        try {
            return documentESRepository.findByUserId(userId);
        } catch (Exception e) {
            log.error("查询用户ES文档失败: userId={}", userId, e);
            return new ArrayList<>();
        }
    }
}
