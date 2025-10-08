package com.ican.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.LocalDateTime;

/**
 * Elasticsearch 文档实体
 * 用于全文检索
 * 
 * @author ICan
 * @since 2024-10-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "ican_documents")
@Setting(shards = 1, replicas = 0)
public class DocumentES {
    
    /**
     * 文档ID（与数据库ID一致）
     */
    @Id
    private Long id;
    
    /**
     * 用户ID
     */
    @Field(type = FieldType.Long)
    private Long userId;
    
    /**
     * 文档标题
     */
    @Field(type = FieldType.Text, analyzer = "standard", searchAnalyzer = "standard")
    private String title;
    
    /**
     * 文档内容（全文）
     */
    @Field(type = FieldType.Text, analyzer = "standard", searchAnalyzer = "standard")
    private String content;
    
    /**
     * 文档类型
     */
    @Field(type = FieldType.Keyword)
    private String type;
    
    /**
     * 文件大小（字节）
     */
    @Field(type = FieldType.Long)
    private Long fileSize;
    
    /**
     * 文档状态
     */
    @Field(type = FieldType.Keyword)
    private String status;
    
    /**
     * 创建时间
     */
    @Field(type = FieldType.Date)
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    @Field(type = FieldType.Date)
    private LocalDateTime updateTime;
}
