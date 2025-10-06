package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.config.RAGConfig;
import com.ican.model.entity.DocumentDO;
import com.ican.model.vo.DocumentVO;
import com.ican.mapper.DocumentMapper;
import com.ican.service.DocumentService;
import com.ican.service.DocumentParserService;
import com.ican.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档服务实现类
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {
    
    private final DocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;
    private final DocumentParserService documentParserService;
    private final FileStorageService fileStorageService;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadDocument(MultipartFile file, String type, Long userId) {
        // 1. 验证文件
        validateFile(file);
        
        // 2. 上传文件到存储服务
        String fileUrl = fileStorageService.uploadFile(file, "documents");
        
        // 3. 创建文档记录
        DocumentDO document = new DocumentDO();
        document.setUserId(userId);
        document.setTitle(file.getOriginalFilename());
        document.setType(type);
        document.setFileSize(file.getSize());
        document.setFileUrl(fileUrl);
        document.setStatus("pending");
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());
        
        documentMapper.insert(document);
        
        log.info("文档上传成功: id={}, title={}, fileUrl={}", document.getId(), document.getTitle(), fileUrl);
        
        // 4. 解析文档内容
        try {
            document.setStatus("processing");
            documentMapper.updateById(document);
            
            String content = documentParserService.parseDocument(file);
            if (StrUtil.isBlank(content)) {
                throw new BusinessException("文档内容为空，无法处理");
            }
            
            vectorizeAndStore(document.getId(), content, document.getUserId());
            
            // 更新状态为已完成
            document.setStatus("completed");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
            
            log.info("文档处理完成: id={}", document.getId());
        } catch (Exception e) {
            log.error("文档处理失败: id={}", document.getId(), e);
            document.setStatus("failed");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
            throw e; // 重新抛出异常
        }
        
        return document.getId();
    }
    
    @Override
    public List<DocumentVO> getUserDocuments(Long userId, String type) {
        LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentDO::getUserId, userId);
        
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(DocumentDO::getType, type);
        }
        
        wrapper.orderByDesc(DocumentDO::getCreateTime);
        
        List<DocumentDO> documents = documentMapper.selectList(wrapper);
        return documents.stream().map(this::convertToVO).collect(Collectors.toList());
    }
    
    @Override
    public DocumentVO getDocument(Long documentId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        
        // 权限验证
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!document.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权访问该文档");
        }
        
        return convertToVO(document);
    }
    
    @Override
    public String parseDocument(Long documentId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        
        try {
            // 从存储服务下载文件
            byte[] fileData = fileStorageService.downloadFile(document.getFileUrl());
            
            // 根据文件类型解析
            String filename = document.getTitle();
            String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
            
            String content;
            // 根据文件扩展名选择解析器
            content = switch (extension) {
                case "pdf" -> documentParserService.parsePDFFromBytes(fileData);
                case "docx", "doc" -> documentParserService.parseWordFromBytes(fileData);
                case "txt" -> documentParserService.parseTextFromBytes(fileData);
                case "md", "markdown" -> documentParserService.parseMarkdownFromBytes(fileData);
                default -> throw new BusinessException("不支持的文件格式: " + extension);
            };
            
            log.info("文档解析成功: id={}, contentLength={}, fileSize={}", documentId, content.length(), fileData.length);
            return content;
        } catch (Exception e) {
            log.error("文档解析失败: id={}", documentId, e);
            throw new BusinessException("文档解析失败: " + e.getMessage());
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void vectorizeAndStore(Long documentId, String content, Long userId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        
        // 验证用户权限
        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("无权处理该文档");
        }
        
        try {
            // 1. 文本分块
            TokenTextSplitter splitter = new TokenTextSplitter(
                ragConfig.getDocument().getChunkSize(),
                ragConfig.getDocument().getChunkOverlap(),
                ragConfig.getDocument().getMinChunkSizeChars(),
                ragConfig.getDocument().getMaxChunkSizeChars(),
                ragConfig.getDocument().getKeepSeparator()
            );
            
            // 将字符串转换为 Document 对象列表
            Document tempDoc = new Document(content);
            List<Document> splitDocs = splitter.split(List.of(tempDoc));
            log.info("文档分块完成: id={}, chunks={}", documentId, splitDocs.size());
            
            // 2. 创建 Document 对象并添加元数据
            List<Document> documents = splitDocs.stream()
                .map(doc -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("documentId", documentId);
                    metadata.put("userId", userId);
                    metadata.put("title", document.getTitle());
                    metadata.put("type", document.getType());
                    metadata.put("timestamp", System.currentTimeMillis());
                    
                    return new Document(doc.getText(), metadata);
                })
                .toList();
            
            // 3. 向量化并存储 - 分批处理以符合阿里云嵌入模型的批量大小限制(最多10个)
            int batchSize = 10; // 阿里云 text-embedding-v4 限制
            int totalBatches = (int) Math.ceil((double) documents.size() / batchSize);
            
            for (int i = 0; i < documents.size(); i += batchSize) {
                int end = Math.min(i + batchSize, documents.size());
                List<Document> batch = documents.subList(i, end);
                int currentBatch = (i / batchSize) + 1;
                
                log.info("向量化批次 {}/{}: 处理 {} 个文档块", currentBatch, totalBatches, batch.size());
                vectorStore.add(batch);
            }
            
            log.info("文档向量化完成: id={}, vectors={}", documentId, documents.size());
        } catch (Exception e) {
            log.error("文档向量化失败: id={}", documentId, e);
            throw new BusinessException("文档向量化失败: " + e.getMessage());
        }
    }
    
    @Override
    public List<Document> searchSimilarDocuments(String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(ragConfig.getRetrieval().getSimilarityThreshold())
                .build();
            
            List<Document> results = vectorStore.similaritySearch(request);
            
            log.info("文档检索完成: query={}, results={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("文档检索失败: query={}", query, e);
            throw new BusinessException("文档检索失败: " + e.getMessage());
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        
        // 权限验证
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!document.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权删除该文档");
        }
        
        try {
            // 1. 从向量库删除 - 通过 metadata 过滤删除
            try {
                // 查询该文档的所有向量
                SearchRequest searchRequest = SearchRequest.builder()
                    .query("") // 空查询
                    .topK(1000) // 获取尽可能多的结果
                    .similarityThreshold(0.0) // 最低阈值
                    .filterExpression(new FilterExpressionBuilder().eq("documentId", documentId).build())
                    .build();
                
                List<Document> documents = vectorStore.similaritySearch(searchRequest);
                
                // 删除这些文档的向量
                if (!documents.isEmpty()) {
                    vectorStore.delete(documents.stream()
                        .map(Document::getId)
                        .toList());
                    log.info("从向量库删除文档向量: documentId={}, count={}", documentId, documents.size());
                }
            } catch (Exception e) {
                log.warn("从向量库删除文档失败: documentId={}", documentId, e);
                // 向量删除失败不影响整体流程
            }
            
            // 2. 从数据库删除
            documentMapper.deleteById(documentId);
            log.info("从数据库删除文档: documentId={}", documentId);
            
            // 3. 从文件存储删除
            try {
                fileStorageService.deleteFile(document.getFileUrl());
                log.info("从文件存储删除文件: documentId={}, fileUrl={}", documentId, document.getFileUrl());
            } catch (Exception e) {
                log.warn("删除文件失败: fileUrl={}", document.getFileUrl(), e);
                // 文件删除失败不影响整体流程
            }
            
            log.info("文档删除成功: id={}", documentId);
        } catch (Exception e) {
            log.error("文档删除失败: id={}", documentId, e);
            throw new BusinessException("文档删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        
        // 验证文件大小
        long maxSize = ragConfig.getDocument().getMaxFileSize() * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new BusinessException("文件大小超过限制: " + ragConfig.getDocument().getMaxFileSize() + "MB");
        }
        
        // 验证文件类型
        String filename = file.getOriginalFilename();
        if (StrUtil.isBlank(filename)) {
            throw new BusinessException("文件名不能为空");
        }
        
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        if (!ragConfig.getDocument().getAllowedTypesList().contains(extension)) {
            throw new BusinessException("不支持的文件类型: " + extension);
        }
    }
    
    /**
     * 转换为 VO
     */
    private DocumentVO convertToVO(DocumentDO document) {
        return DocumentVO.builder()
            .id(document.getId())
            .title(document.getTitle())
            .type(document.getType())
            .fileSize(document.getFileSize())
            .status(document.getStatus())
            .createTime(document.getCreateTime())
            .updateTime(document.getUpdateTime())
            .build();
    }
}

