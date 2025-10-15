package com.ican.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储配置
 * 
 * @author 席崇援
 * @since 2024-10-07
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageProperties {
    
    /**
     * 本地存储基础路径
     */
    private String basePath = "./uploads";
    
    /**
     * 是否启用 MinIO
     */
    private Boolean minioEnabled = false;
    
    /**
     * MinIO 配置
     */
    private MinioConfig minio = new MinioConfig();
    
    @Data
    public static class MinioConfig {
        /**
         * MinIO 服务地址
         */
        private String endpoint;
        
        /**
         * 访问密钥
         */
        private String accessKey;
        
        /**
         * 秘密密钥
         */
        private String secretKey;
        
        /**
         * 存储桶名称
         */
        private String bucket;
    }
}
