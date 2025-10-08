package com.ican.mq;

import com.ican.config.RabbitMQConfig;
import com.ican.model.dto.DocumentProcessingMessage;
import com.ican.model.entity.DocumentDO;
import com.ican.mapper.DocumentMapper;
import com.ican.service.DocumentService;
import com.ican.service.DocumentESService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档处理消费者
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessingConsumer {
    
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final DocumentESService documentESService;
    
    /**
     * 处理文档
     * 流程: 解析 → 向量化 → ES索引 → 更新状态
     */
    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_PROCESSING_QUEUE)
    public void processDocument(DocumentProcessingMessage message) {
        log.info("收到文档处理消息: documentId={}, type={}", 
            message.getDocumentId(), message.getProcessingType());
        
        Long documentId = message.getDocumentId();
        DocumentDO document = null;
        
        try {
            // 1. 查询文档
            document = documentMapper.selectById(documentId);
            if (document == null) {
                log.error("文档不存在: documentId={}", documentId);
                return;
            }
            
            // 2. 更新状态为处理中
            document.setStatus("processing");
            documentMapper.updateById(document);
            log.info("开始处理文档: documentId={}, title={}", documentId, document.getTitle());
            
            // 3. 解析文档内容
            String content = documentService.parseDocument(documentId);
            log.info("文档解析完成: documentId={}, contentLength={}", documentId, content.length());
            
            // 4. 向量化并存储到向量数据库
            documentService.vectorizeAndStore(documentId, content, document.getUserId());
            log.info("文档向量化完成: documentId={}", documentId);
            
            // 5. 索引到Elasticsearch
            try {
                documentESService.indexDocument(
                    documentId,
                    document.getUserId(),
                    document.getTitle(),
                    content,
                    document.getType(),
                    document.getFileSize(),
                    "completed"
                );
                log.info("文档索引到ES完成: documentId={}", documentId);
            } catch (Exception esError) {
                log.error("ES索引失败，但不影响主流程: documentId={}", documentId, esError);
                // ES失败不影响主流程，继续执行
            }
            
            // 6. 更新状态为完成
            document.setStatus("completed");
            documentMapper.updateById(document);
            
            log.info("文档处理完成: documentId={}, title={}", documentId, document.getTitle());
            
        } catch (Exception e) {
            log.error("文档处理失败: documentId={}", documentId, e);
            
            // 更新状态为失败
            try {
                if (document == null) {
                    document = documentMapper.selectById(documentId);
                }
                if (document != null) {
                    document.setStatus("failed");
                    documentMapper.updateById(document);
                    
                    // 同步更新ES状态
                    try {
                        documentESService.updateDocumentStatus(documentId, "failed");
                    } catch (Exception ignored) {
                        // 忽略ES更新失败
                    }
                }
            } catch (Exception updateError) {
                log.error("更新失败状态异常: documentId={}", documentId, updateError);
            }
        }
    }
}

