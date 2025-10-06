package com.ican.mq;

import com.ican.config.RabbitMQConfig;
import com.ican.model.dto.DocumentProcessingMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 文档处理生产者
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessingProducer {
    
    private final RabbitTemplate rabbitTemplate;
    
    /**
     * 发送文档处理消息
     * 
     * @param documentId 文档ID
     * @param userId 用户ID
     */
    public void sendProcessingTask(Long documentId, Long userId) {
        DocumentProcessingMessage message = DocumentProcessingMessage.builder()
            .documentId(documentId)
            .userId(userId)
            .processingType("full")
            .build();
        
        rabbitTemplate.convertAndSend(RabbitMQConfig.DOCUMENT_PROCESSING_QUEUE, message);
        
        log.info("发送文档处理任务: documentId={}, userId={}", documentId, userId);
    }
}

