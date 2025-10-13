package com.ican.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base62;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ican.config.RAGConfig;
import com.ican.model.dto.DocumentQueryDTO;
import com.ican.model.entity.DocumentChunkDO;
import com.ican.model.entity.DocumentDO;
import com.ican.model.entity.DocumentVectorDO;
import com.ican.model.vo.DocumentFileVO;
import com.ican.model.vo.DocumentVO;
import com.ican.mapper.DocumentChunkMapper;
import com.ican.mapper.DocumentMapper;
import com.ican.mapper.DocumentVectorMapper;
import com.ican.service.DocumentService;
import com.ican.service.DocumentParserService;
import com.ican.service.FileStorageService;
import com.ican.service.DocumentTaskService;
import com.ican.mq.DocumentProcessingProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档服务实现类
 * 
 * @author 席崇援
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
    private final DocumentVectorMapper documentVectorMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentProcessingProducer documentProcessingProducer;
    private final DocumentTaskService documentTaskService;
    
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
        
        // 4. 创建任务跟踪记录
        Long taskId = documentTaskService.createTask(document.getId(), "document_processing");
        log.info("创建文档处理任务: documentId={}, taskId={}", document.getId(), taskId);
        
        // 5. 更新状态为处理中（在事务内完成）
        document.setStatus("processing");
        documentMapper.updateById(document);
        
        // 更新任务状态为处理中
        documentTaskService.updateTaskStatus(taskId, "processing", 10, null);
        
        Long documentId = document.getId();
        
        // 6. 注册事务提交后的回调：发送异步处理消息
        // 使用 TransactionSynchronizationManager 确保消息在事务提交后才发送
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 事务已提交，现在可以安全地发送消息了
                        documentProcessingProducer.sendDocumentProcessingMessage(documentId, userId, taskId);
                        log.info("文档已提交异步处理队列（事务已提交）: id={}", documentId);
                    } catch (Exception e) {
                        log.error("提交文档处理消息失败: id={}", documentId, e);
                        // 注意：这里事务已经提交，无法回滚
                        // 需要通过补偿机制处理，例如更新状态为failed
                        try {
                            DocumentDO failedDoc = documentMapper.selectById(documentId);
                            if (failedDoc != null) {
                                failedDoc.setStatus("failed");
                                failedDoc.setUpdateTime(LocalDateTime.now());
                                documentMapper.updateById(failedDoc);
                            }
                            documentTaskService.updateTaskStatus(taskId, "failed", 0, e.getMessage());
                        } catch (Exception ex) {
                            log.error("更新失败状态异常: id={}", documentId, ex);
                        }
                    }
                }
            }
        );
        
        return documentId;
    }
    
    @Override
    public IPage<DocumentVO> pageUserDocuments(Long userId, DocumentQueryDTO queryDTO) {
        // 创建分页对象
        Page<DocumentDO> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        
        // 构建查询条件
        LambdaQueryWrapper<DocumentDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentDO::getUserId, userId)
               .eq(DocumentDO::getIsDeleted, 0);
        
        // 标题模糊查询
        if (StrUtil.isNotBlank(queryDTO.getTitle())) {
            wrapper.like(DocumentDO::getTitle, queryDTO.getTitle());
        }
        
        // 类型过滤
        if (StrUtil.isNotBlank(queryDTO.getType())) {
            wrapper.eq(DocumentDO::getType, queryDTO.getType());
        }
        
        // 状态过滤
        if (StrUtil.isNotBlank(queryDTO.getStatus())) {
            wrapper.eq(DocumentDO::getStatus, queryDTO.getStatus());
        }
        
        // 知识库ID过滤
        if (queryDTO.getKbId() != null) {
            wrapper.eq(DocumentDO::getKbId, queryDTO.getKbId());
        }
        
        // 排序
        String sortField = queryDTO.getSortField();
        String sortOrder = queryDTO.getSortOrder();
        if (StrUtil.isNotBlank(sortField)) {
            if ("desc".equalsIgnoreCase(sortOrder)) {
                wrapper.orderByDesc(getColumnByField(sortField));
            } else {
                wrapper.orderByAsc(getColumnByField(sortField));
            }
        } else {
            // 默认按创建时间降序
            wrapper.orderByDesc(DocumentDO::getCreateTime);
        }
        
        // 执行分页查询
        IPage<DocumentDO> documentPage = documentMapper.selectPage(page, wrapper);
        
        // 转换为 VO
        IPage<DocumentVO> voPage = new Page<>(documentPage.getCurrent(), documentPage.getSize(), documentPage.getTotal());
        voPage.setRecords(documentPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList()));
        
        return voPage;
    }
    
    /**
     * 根据字段名获取列函数（用于动态排序）
     */
    private com.baomidou.mybatisplus.core.toolkit.support.SFunction<DocumentDO, ?> getColumnByField(String field) {
        return switch (field) {
            case "title" -> DocumentDO::getTitle;
            case "type" -> DocumentDO::getType;
            case "status" -> DocumentDO::getStatus;
            case "fileSize" -> DocumentDO::getFileSize;
            case "updateTime" -> DocumentDO::getUpdateTime;
            default -> DocumentDO::getCreateTime;
        };
    }
    
    @Override
    public DocumentVO getDocument(Long documentId) {
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null || (document.getIsDeleted() != null && document.getIsDeleted() == 1)) {
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
        if (document == null || (document.getIsDeleted()!=null && document.getIsDeleted()==1)) {
            throw new BusinessException("文档不存在");
        }
        
        try {
            // 从存储服务下载文件
            byte[] fileData = fileStorageService.downloadFile(document.getFileUrl());
            
            // 根据文件类型解析
            String filename = document.getTitle();
            int dot = filename.lastIndexOf('.');
            if (dot < 0 || dot == filename.length()-1) {
                throw new BusinessException("无法识别的文件类型");
            }
            String extension = filename.substring(dot + 1).toLowerCase();
            
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
            
            // 2. 创建 Document 对象并添加元数据（优化：带分块索引）
            // 重要：使用 Base62 编码存储 Long ID，避免向量库 Double 精度丢失
            // 原理：Long -> Base62字符串，完全避免精度问题（1976521493122658306 -> "aDeMo8kL6")
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < splitDocs.size(); i++) {
                Document doc = splitDocs.get(i);
                Map<String, Object> metadata = new HashMap<>();
                // 使用 Hutool Base62 编码 Long ID
                metadata.put("documentId", Base62.encode(String.valueOf(documentId)));
                metadata.put("userId", Base62.encode(String.valueOf(userId)));
                metadata.put("title", document.getTitle());
                metadata.put("type", document.getType());
                metadata.put("chunkIndex", i);  // 添加分块索引
                metadata.put("timestamp", System.currentTimeMillis());
                
                documents.add(new Document(doc.getText(), metadata));
            }
            
            // 3. 向量化并存储 - 分批处理以符合阿里云嵌入模型的批量大小限制(最多10个)
            // 优化：记录每个向量的ID和分块内容到数据库
            int batchSize = 10; // 阿里云 text-embedding-v4 限制
            int totalBatches = (int) Math.ceil((double) documents.size() / batchSize);
            
            for (int i = 0; i < documents.size(); i += batchSize) {
                int end = Math.min(i + batchSize, documents.size());
                List<Document> batch = documents.subList(i, end);
                int currentBatch = (i / batchSize) + 1;
                
                log.info("向量化批次 {}/{}: 处理 {} 个文档块", currentBatch, totalBatches, batch.size());
                
                // 添加到向量库（向量库会为每个Document生成ID）
                vectorStore.add(batch);
                
                // 保存向量ID映射和分块内容到数据库
                for (Document doc : batch) {
                    try {
                        Integer chunkIndex = (Integer) doc.getMetadata().get("chunkIndex");
                        String vectorId = doc.getId();
                        
                        // 保存向量ID映射（用于后续删除）
                        DocumentVectorDO vectorMapping = DocumentVectorDO.builder()
                            .documentId(documentId)
                            .vectorId(vectorId)
                            .chunkIndex(chunkIndex)
                            .createTime(LocalDateTime.now())
                            .build();
                        documentVectorMapper.insert(vectorMapping);
                        
                        // 保存分块内容到document_chunks表
                        DocumentChunkDO chunk = DocumentChunkDO.builder()
                            .documentId(documentId)
                            .chunkIndex(chunkIndex)
                            .content(doc.getText())
                            .vectorId(vectorId)
                            .tokens(doc.getText().length() / 4)  // 粗略估算token数
                            .metadata(doc.getMetadata())
                            .createTime(LocalDateTime.now())
                            .isDeleted(0)
                            .build();
                        documentChunkMapper.insert(chunk);
                        
                    } catch (Exception e) {
                        log.warn("保存文档块数据失败: documentId={}, vectorId={}", documentId, doc.getId(), e);
                        // 不影响主流程，向量已经存储
                    }
                }
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
            // 获取配置的相似度阈值
            double threshold = ragConfig.getRetrieval().getSimilarityThreshold();
            log.info("开始文档检索: query={}, topK={}, similarityThreshold={}", query, topK, threshold);
            
            // 构建检索请求
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
            
            // 执行向量检索
            List<Document> results = vectorStore.similaritySearch(request);
            
            if (results.isEmpty()) {
                log.warn("未找到相似文档: query={}, threshold={}, 建议降低相似度阈值或检查向量库中是否有数据", 
                    query, threshold);
                return results;
            }
            // 输出检索结果详情（包含相似度得分）
            log.info("文档检索完成: query={}, results={}, threshold={}", query, results.size(), threshold);
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                if (doc.getText() != null) {
                    log.debug("检索结果[{}]: score={}, content={}, metadata={}",
                        i, doc.getScore(),
                        doc.getText().substring(0, Math.min(100, doc.getText().length())),
                        doc.getMetadata());
                }
            }
            
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
            // 1. 从向量库删除（优化：使用映射表快速获取向量ID）
            try {
                // 从映射表查询该文档的所有向量ID
                List<DocumentVectorDO> vectorMappings = documentVectorMapper.selectList(
                    new LambdaQueryWrapper<DocumentVectorDO>()
                        .eq(DocumentVectorDO::getDocumentId, documentId)
                );
                
                if (!vectorMappings.isEmpty()) {
                    // 提取向量ID列表
                    List<String> vectorIds = vectorMappings.stream()
                        .map(DocumentVectorDO::getVectorId)
                        .toList();
                    
                    // 批量删除向量
                    vectorStore.delete(vectorIds);
                    log.info("从向量库删除文档向量: documentId={}, count={}", documentId, vectorIds.size());
                    
                    // 删除映射记录
                    documentVectorMapper.delete(new LambdaQueryWrapper<DocumentVectorDO>()
                        .eq(DocumentVectorDO::getDocumentId, documentId));
                } else {
                    log.warn("未找到文档向量映射记录，尝试通过metadata查询删除: documentId={}", documentId);
                    
                    // 后备方案：通过metadata查询删除（兼容旧数据）
                    SearchRequest searchRequest = SearchRequest.builder()
                        .query("") // 空查询
                        .topK(1000) // 获取尽可能多的结果
                        .similarityThreshold(0.0) // 最低阈值
                        .filterExpression(new FilterExpressionBuilder().eq("documentId", 
                            Base62.encode(String.valueOf(documentId))).build())
                        .build();
                    
                    List<Document> documents = vectorStore.similaritySearch(searchRequest);
                    if (!documents.isEmpty()) {
                        vectorStore.delete(documents.stream()
                            .map(Document::getId)
                            .toList());
                        log.info("通过metadata删除文档向量: documentId={}, count={}", documentId, documents.size());
                    }
                }
            } catch (Exception e) {
                log.error("从向量库删除文档失败: documentId={}", documentId, e);
                // 向量删除失败不影响数据库删除
            }
            documentMapper.deleteById(documentId);
            log.info("逻辑删除文档: documentId={}", documentId);
            
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
        
        // 验证是否已上传（检查当前用户是否已有同名且同大小的文件）
        Long currentUserId = StpUtil.getLoginIdAsLong();
        DocumentDO existingDoc = documentMapper.selectOne(
            new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getUserId, currentUserId)
                .eq(DocumentDO::getTitle, filename)
                .eq(DocumentDO::getFileSize, file.getSize())
                .eq(DocumentDO::getIsDeleted, 0)
                .last("limit 1")
        );
        
        if (existingDoc != null) {
            throw new BusinessException("文件已存在: " + filename + " (大小: " + file.getSize() + " 字节)");
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
    
    /**
     * RAG-05: 重建文档向量索引
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reindexDocument(Long documentId) {
        log.info("开始重建文档向量索引: documentId={}", documentId);
        
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null || (document.getIsDeleted()!=null && document.getIsDeleted()==1)) {
            throw new BusinessException("文档不存在");
        }
        
        // 权限验证
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!document.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权操作该文档");
        }
        
        try {
            // 标记处理中
            document.setStatus("processing");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
            // 1. 先清除旧向量
            purgeDocumentVectors(documentId);
            
            // 2. 重新解析文档（使用现有方法）
            String content = parseDocument(documentId);
            
            if (StrUtil.isBlank(content)) {
                throw new BusinessException("文档内容为空，无法建立索引");
            }
            
            // 3. 重新向量化
            vectorizeAndStore(documentId, content, document.getUserId());
            
            // 4. 更新文档状态
            document.setStatus("completed");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
            
            // 5. 查询新向量数量
            int vectorCount = documentVectorMapper.selectCount(
                new LambdaQueryWrapper<DocumentVectorDO>()
                    .eq(DocumentVectorDO::getDocumentId, documentId)
            ).intValue();
            
            log.info("文档向量索引重建成功: documentId={}, vectorCount={}", documentId, vectorCount);
            return vectorCount;
            
        } catch (Exception e) {
            log.error("重建文档向量索引失败: documentId={}", documentId, e);
            document.setStatus("failed");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
            throw new BusinessException("重建索引失败: " + e.getMessage());
        }
    }
    
    /**
     * RAG-05: 清除文档向量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeDocumentVectors(Long documentId) {
        log.info("开始清除文档向量: documentId={}", documentId);
        
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null || (document.getIsDeleted()!=null && document.getIsDeleted()==1)) {
            throw new BusinessException("文档不存在");
        }
        
        // 权限验证
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!document.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权操作该文档");
        }
        
        int deletedCount = 0;
        
        try {
            // 从映射表查询向量ID
            List<DocumentVectorDO> vectorMappings = documentVectorMapper.selectList(
                new LambdaQueryWrapper<DocumentVectorDO>()
                    .eq(DocumentVectorDO::getDocumentId, documentId)
            );
            
            if (!vectorMappings.isEmpty()) {
                // 提取向量ID
                List<String> vectorIds = vectorMappings.stream()
                    .map(DocumentVectorDO::getVectorId)
                    .toList();
                
                // 从向量库删除
                vectorStore.delete(vectorIds);
                deletedCount = vectorIds.size();
                
                // 删除映射记录
                documentVectorMapper.delete(
                    new LambdaQueryWrapper<DocumentVectorDO>()
                        .eq(DocumentVectorDO::getDocumentId, documentId)
                );
                
                // 删除块记录
                documentChunkMapper.delete(
                    new LambdaQueryWrapper<DocumentChunkDO>()
                        .eq(DocumentChunkDO::getDocumentId, documentId)
                );
                
                log.info("文档向量清除成功: documentId={}, count={}", documentId, deletedCount);
            } else {
                log.info("文档没有向量记录: documentId={}", documentId);
            }
            
            return deletedCount;
            
        } catch (Exception e) {
            log.error("清除文档向量失败: documentId={}", documentId, e);
            throw new BusinessException("清除向量失败: " + e.getMessage());
        }
    }
    
    /**
     * RAG-05: 批量重建向量索引
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchReindexDocuments(List<Long> documentIds) {
        log.info("开始批量重建向量索引: documentIds={}", documentIds);
        
        if (documentIds == null || documentIds.isEmpty()) {
            return 0;
        }
        
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        
        for (Long documentId : documentIds) {
            try {
                reindexDocument(documentId);
                successCount++;
            } catch (Exception e) {
                log.error("重建文档索引失败: documentId={}", documentId, e);
                errors.add("文档 " + documentId + ": " + e.getMessage());
            }
        }
        
        log.info("批量重建完成: total={}, success={}, failed={}", 
            documentIds.size(), successCount, documentIds.size() - successCount);
        
        if (!errors.isEmpty()) {
            throw new BusinessException("部分文档重建失败: " + String.join("; ", errors));
        }
        
        return successCount;
    }
    
    /**
     * 获取文档文件用于预览或下载
     */
    @Override
    public DocumentFileVO getDocumentFile(Long documentId) {
        log.info("获取文档文件: documentId={}", documentId);
        
        // 1. 查询文档记录
        DocumentDO document = documentMapper.selectById(documentId);
        if (document == null || (document.getIsDeleted() != null && document.getIsDeleted() == 1)) {
            throw new BusinessException("文档不存在");
        }
        
        // 2. 权限验证
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!document.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权访问该文档");
        }
        
        try {
            // 3. 从存储服务下载文件
            byte[] fileData = fileStorageService.downloadFile(document.getFileUrl());
            
            // 4. 确定内容类型
            String filename = document.getTitle();
            String contentType = determineContentType(filename);
            
            log.info("文档文件获取成功: documentId={}, filename={}, size={}", 
                    documentId, filename, fileData.length);
            
            return DocumentFileVO.builder()
                    .filename(filename)
                    .contentType(contentType)
                    .data(fileData)
                    .build();
                    
        } catch (Exception e) {
            log.error("获取文档文件失败: documentId={}", documentId, e);
            throw new BusinessException("获取文档文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据文件名确定内容类型
     */
    private String determineContentType(String filename) {
        if (StrUtil.isBlank(filename)) {
            return "application/octet-stream";
        }
        
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "application/octet-stream";
        }
        
        String extension = filename.substring(dot + 1).toLowerCase();
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }
}

