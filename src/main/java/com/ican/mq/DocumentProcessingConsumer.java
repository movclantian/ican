package com.ican.mq;

import com.ican.config.RabbitMQConfig;
import com.ican.model.dto.DocumentProcessingMessage;
import com.ican.model.entity.DocumentDO;
import com.ican.mapper.DocumentMapper;
import com.ican.service.DocumentService;
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
    
    /**
     * 处理文档
     */
    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_PROCESSING_QUEUE)
    public void processDocument(DocumentProcessingMessage message) {
        log.info("收到文档处理消息: documentId={}, type={}", 
            message.getDocumentId(), message.getProcessingType());
        
        try {
            DocumentDO document = documentMapper.selectById(message.getDocumentId());
            if (document == null) {
                log.error("文档不存在: documentId={}", message.getDocumentId());
                return;
            }
            
            // 更新状态
            document.setStatus("processing");
            documentMapper.updateById(document);
            
            // 解析并向量化
            String content = documentService.parseDocument(message.getDocumentId());
            documentService.vectorizeAndStore(message.getDocumentId(), content, document.getUserId());
            
            // 更新状态为完成
            document.setStatus("completed");
            documentMapper.updateById(document);
            
            log.info("文档处理完成: documentId={}", message.getDocumentId());
        } catch (Exception e) {
            log.error("文档处理失败: documentId={}", message.getDocumentId(), e);
            
            // 更新状态为失败
            DocumentDO document = documentMapper.selectById(message.getDocumentId());
            if (document != null) {
                document.setStatus("failed");
                documentMapper.updateById(document);
            }
        }
    }
}

