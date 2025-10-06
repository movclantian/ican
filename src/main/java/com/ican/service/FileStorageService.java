package com.ican.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口
 * 
 * @author ICan
 * @since 2024-10-06
 */
public interface FileStorageService {
    
    /**
     * 上传文件
     * 
     * @param file 文件
     * @param bucket 存储桶名称
     * @return 文件URL
     */
    String uploadFile(MultipartFile file, String bucket);
    
    /**
     * 下载文件
     * 
     * @param fileUrl 文件URL
     * @return 文件字节数组
     */
    byte[] downloadFile(String fileUrl);
    
    /**
     * 删除文件
     * 
     * @param fileUrl 文件URL
     */
    void deleteFile(String fileUrl);
}

