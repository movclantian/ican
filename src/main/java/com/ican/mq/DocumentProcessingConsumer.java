package com.ican.mq;

import com.ican.config.RabbitMQConfig;
import com.ican.model.dto.DocumentProcessingMessage;
import com.ican.model.entity.DocumentDO;
import com.ican.mapper.DocumentMapper;
import com.ican.service.DocumentService;
import com.ican.service.DocumentESService;
import com.ican.service.DocumentTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档处理消费者
 * 
 * @author 席崇援
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessingConsumer {
    
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final DocumentESService documentESService;
    private final DocumentTaskService documentTaskService;
    
    /**
     * 处理文档
     * 流程: 解析 → 向量化 → ES索引 → 更新状态
     */
    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_PROCESSING_QUEUE)
    public void processDocument(DocumentProcessingMessage message) {
        log.info("收到文档处理消息: documentId={}, type={}", 
            message.getDocumentId(), message.getProcessingType());
        
        Long documentId = message.getDocumentId();
        Long taskId = message.getTaskId(); // 从消息中获取taskId
        DocumentDO document = null;
        
        try {
            // 1. 查询文档
            document = documentMapper.selectById(documentId);
            if (document == null) {
                log.error("文档不存在: documentId={}", documentId);
                if (taskId != null) {
                    documentTaskService.updateTaskStatus(taskId, "failed", 0, "文档不存在");
                }
                return;
            }
            
            // 2. 更新状态为处理中（进度20%）
            document.setStatus("processing");
            documentMapper.updateById(document);
            if (taskId != null) {
                documentTaskService.updateTaskStatus(taskId, "processing", 20, null);
            }
            log.info("开始处理文档: documentId={}, title={}", documentId, document.getTitle());
            
            // 3. 解析文档内容（进度40%）
            String content = documentService.parseDocument(documentId);
            if (taskId != null) {
                documentTaskService.updateTaskStatus(taskId, "processing", 40, null);
            }
            log.info("文档解析完成: documentId={}, contentLength={}", documentId, content.length());
            
            // 4. 向量化并存储到向量数据库（进度70%）
            documentService.vectorizeAndStore(documentId, content, document.getUserId());
            if (taskId != null) {
                documentTaskService.updateTaskStatus(taskId, "processing", 70, null);
            }
            log.info("文档向量化完成: documentId={}", documentId);
            
            // 5. 索引到Elasticsearch（进度90%）
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
                if (taskId != null) {
                    documentTaskService.updateTaskStatus(taskId, "processing", 90, null);
                }
                log.info("文档索引到ES完成: documentId={}", documentId);
            } catch (Exception esError) {
                log.error("ES索引失败，但不影响主流程: documentId={}", documentId, esError);
                // ES失败影响主流程，不继续执行
                //更新错误信息
                if (taskId != null) {
                    documentTaskService.updateTaskStatus(taskId, "failed", 90, "ES索引失败: " + esError.getMessage());
                }
                return;
            }
            
            // 6. 更新状态为完成（进度100%）
            document.setStatus("completed");
            documentMapper.updateById(document);
            if (taskId != null) {
                documentTaskService.updateTaskStatus(taskId, "completed", 100, null);
            }
            
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
                
                // 更新任务状态为失败
                if (taskId != null) {
                    documentTaskService.updateTaskStatus(taskId, "failed", 0, e.getMessage());
                }
            } catch (Exception updateError) {
                log.error("更新失败状态异常: documentId={}", documentId, updateError);
            }
        }
    }
}

