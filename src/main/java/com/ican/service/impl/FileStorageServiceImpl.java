package com.ican.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ican.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 文件存储服务实现 - 本地存储版本
 * 后续可替换为 MinIO 实现
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {
    
    @Value("${file.storage.base-path:./uploads}")
    private String basePath;
    
    @Override
    public String uploadFile(MultipartFile file, String bucket) {
        try {
            // 1. 创建目录
            Path bucketPath = Paths.get(basePath, bucket);
            if (!Files.exists(bucketPath)) {
                Files.createDirectories(bucketPath);
            }
            
            // 2. 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String filename = IdUtil.fastSimpleUUID() + extension;
            
            // 3. 保存文件
            Path filePath = bucketPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            
            // 4. 返回相对路径
            String fileUrl = bucket + "/" + filename;
            
            log.info("文件上传成功: originalName={}, fileUrl={}", originalFilename, fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }
    
    @Override
    public byte[] downloadFile(String fileUrl) {
        try {
            Path filePath = Paths.get(basePath, fileUrl);
            
            if (!Files.exists(filePath)) {
                throw new BusinessException("文件不存在: " + fileUrl);
            }
            
            byte[] data = Files.readAllBytes(filePath);
            
            log.debug("文件下载成功: fileUrl={}, size={}", fileUrl, data.length);
            return data;
        } catch (IOException e) {
            log.error("文件下载失败: fileUrl={}", fileUrl, e);
            throw new BusinessException("文件下载失败: " + e.getMessage());
        }
    }
    
    @Override
    public void deleteFile(String fileUrl) {
        try {
            Path filePath = Paths.get(basePath, fileUrl);
            
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("文件删除成功: fileUrl={}", fileUrl);
            } else {
                log.warn("文件不存在,无需删除: fileUrl={}", fileUrl);
            }
        } catch (IOException e) {
            log.error("文件删除失败: fileUrl={}", fileUrl, e);
            throw new BusinessException("文件删除失败: " + e.getMessage());
        }
    }
}

