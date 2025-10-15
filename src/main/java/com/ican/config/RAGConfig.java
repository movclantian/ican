package com.ican.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG 配置类
 * 
 * @author 席崇援
 * @since 2024-10-06
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RAGConfig {
    
    /**
     * 向量检索配置
     */
    private RetrievalConfig retrieval = new RetrievalConfig();
    
    /**
     * 文档处理配置
     */
    private DocumentConfig document = new DocumentConfig();
    
    /**
     * 嵌入模型配置
     */
    private EmbeddingConfig embedding = new EmbeddingConfig();
    
    /**
     * 混合搜索配置
     */
    private HybridSearchConfig hybridSearch = new HybridSearchConfig();
    
    @Data
    public static class RetrievalConfig {
        /**
         * 默认检索文档数量
         */
        private Integer topK = 5;
        
        /**
         * 相似度阈值
         */
        private Double similarityThreshold = 0.3;
        
        /**
         * 是否启用重排序
         */
        private Boolean enableReranking = true;
        
        /**
         * 重排序扩展因子（初始检索数量 = topK * expandFactor）
         */
        private Integer rerankExpandFactor = 6;
        
        /**
         * 是否启用混合检索
         */
        private Boolean enableHybridSearch = false;
    }
    
    @Data
    public static class DocumentConfig {
        /**
         * 文档分块大小(token)
         */
        private Integer chunkSize = 500;
        
        /**
         * 块间重叠(token)
         */
        private Integer chunkOverlap = 100;
        
        /**
         * 最小分块字符数
         */
        private Integer minChunkSizeChars = 5;
        
        /**
         * 最大分块字符数
         */
        private Integer maxChunkSizeChars = 1000;
        
        /**
         * 是否保留分隔符
         */
        private Boolean keepSeparator = true;
        
        /**
         * 支持的文件类型
         */
        private String allowedTypes = "pdf,docx,md,txt";
        
        /**
         * 单个文件最大大小(MB)
         */
        private Integer maxFileSize = 50;
        
        /**
         * 获取允许的文件类型列表
         */
        public List<String> getAllowedTypesList() {
            return List.of(allowedTypes.split(","));
        }
    }
    
    @Data
    public static class EmbeddingConfig {
        /**
         * 模型名称
         */
        private String model = "text-embedding-v4";
        
        /**
         * 批量处理大小
         */
        private Integer batchSize = 100;
        
        /**
         * 向量维度
         */
        private Integer dimension = 1536;
    }
    
    @Data
    public static class HybridSearchConfig {
        /**
         * 向量搜索数量
         */
        private Integer vectorTopK = 20;
        
        /**
         * 向量相似度阈值
         */
        private Double vectorSimilarityThreshold = 0.5;
        
        /**
         * 向量搜索权重
         */
        private Double vectorWeight = 0.6;
        
        /**
         * 全文搜索权重
         */
        private Double textWeight = 0.4;
    }
}

