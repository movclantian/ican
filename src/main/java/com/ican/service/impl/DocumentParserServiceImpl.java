package com.ican.service.impl;

import com.ican.service.DocumentParserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析服务实现
 * 
 * @author 席崇援
 */
@Slf4j
@Service
public class DocumentParserServiceImpl implements DocumentParserService {
    
    @Override
    public String parseDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new BusinessException("文件名不能为空");
        }
        
        // 检查是否有扩展名
        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            throw new BusinessException("文件缺少扩展名");
        }
        
        String extension = filename.substring(lastDotIndex + 1).toLowerCase();
        
        return switch (extension) {
            case "pdf" -> parsePDF(file);
            case "docx", "doc" -> parseWord(file);
            case "md", "markdown" -> parseMarkdown(file);
            case "txt" -> parseText(file);
            default -> throw new BusinessException("不支持的文件格式: " + extension);
        };
    }
    
    @Override
    public String parsePDF(MultipartFile file) {
        try {
            log.info("开始解析PDF: {}", file.getOriginalFilename());
            
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                
                // 设置排序
                stripper.setSortByPosition(true);
                
                String text = stripper.getText(document);
                
                // 清理文本
                text = cleanText(text);
                
                log.info("PDF解析完成: {}, 字符数={}", file.getOriginalFilename(), text.length());
                return text;
            }
        } catch (Exception e) {
            log.error("PDF解析失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException("PDF解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseWord(MultipartFile file) {
        try {
            log.info("开始解析Word: {}", file.getOriginalFilename());
            
            try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
                StringBuilder text = new StringBuilder();
                
                // 提取所有段落
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    String paragraphText = paragraph.getText();
                    if (paragraphText != null && !paragraphText.trim().isEmpty()) {
                        text.append(paragraphText).append("\n");
                    }
                }
                
                String result = cleanText(text.toString());
                
                log.info("Word解析完成: {}, 字符数={}", file.getOriginalFilename(), result.length());
                return result;
            }
        } catch (Exception e) {
            log.error("Word解析失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException("Word解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseMarkdown(MultipartFile file) {
        try {
            log.info("开始解析Markdown: {}", file.getOriginalFilename());
            
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append("\n");
                }
            }
            
            String result = text.toString();
            
            log.info("Markdown解析完成: {}, 字符数={}", file.getOriginalFilename(), result.length());
            return result;
        } catch (Exception e) {
            log.error("Markdown解析失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException("Markdown解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseText(MultipartFile file) {
        try {
            log.info("开始解析文本: {}", file.getOriginalFilename());
            
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append("\n");
                }
            }
            
            String result = text.toString();
            
            log.info("文本解析完成: {}, 字符数={}", file.getOriginalFilename(), result.length());
            return result;
        } catch (Exception e) {
            log.error("文本解析失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException("文本解析失败: " + e.getMessage());
        }
    }
    
    /**
     * 清理文本
     * - 移除多余空白
     * - 标准化换行
     * - 移除特殊字符
     */
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        
        return text
            // 移除特殊控制字符（保留换行符和制表符）
            .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
            // 移除多余的空行（保留最多两个连续换行）
            .replaceAll("\\n{3,}", "\n\n")
            // 移除行首行尾空格和制表符
            .replaceAll("(?m)^[\\s\\t]+|[\\s\\t]+$", "")
            // 标准化多个空格为单个空格
            .replaceAll("[\\s\\t]{2,}", " ")
            // 移除多余的制表符
            .replaceAll("\\t+", " ")
            // 最终trim
            .trim();
    }
    
    @Override
    public String parsePDFFromBytes(byte[] fileData) {
        try {
            log.info("开始从字节数组解析PDF, 大小={} bytes", fileData.length);
            
            PDDocument document = Loader.loadPDF(fileData);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            
            String text = stripper.getText(document);
            document.close();
            
            text = cleanText(text);
            
            log.info("PDF解析完成, 字符数={}", text.length());
            return text;
        } catch (Exception e) {
            log.error("PDF字节数组解析失败", e);
            throw new BusinessException("PDF解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseWordFromBytes(byte[] fileData) {
        try {
            log.info("开始从字节数组解析Word, 大小={} bytes", fileData.length);
            
            XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(fileData));
            StringBuilder text = new StringBuilder();
            
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paragraphText = paragraph.getText();
                if (paragraphText != null && !paragraphText.trim().isEmpty()) {
                    text.append(paragraphText).append("\n");
                }
            }
            
            document.close();
            
            String result = cleanText(text.toString());
            
            log.info("Word解析完成, 字符数={}", result.length());
            return result;
        } catch (Exception e) {
            log.error("Word字节数组解析失败", e);
            throw new BusinessException("Word解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseTextFromBytes(byte[] fileData) {
        try {
            log.info("开始从字节数组解析文本, 大小={} bytes", fileData.length);
            
            String text = new String(fileData, StandardCharsets.UTF_8);
            
            log.info("文本解析完成, 字符数={}", text.length());
            return text;
        } catch (Exception e) {
            log.error("文本字节数组解析失败", e);
            throw new BusinessException("文本解析失败: " + e.getMessage());
        }
    }
    
    @Override
    public String parseMarkdownFromBytes(byte[] fileData) {
        try {
            log.info("开始从字节数组解析Markdown, 大小={} bytes", fileData.length);
            
            String text = new String(fileData, StandardCharsets.UTF_8);
            
            log.info("Markdown解析完成, 字符数={}", text.length());
            return text;
        } catch (Exception e) {
            log.error("Markdown字节数组解析失败", e);
            throw new BusinessException("Markdown解析失败: " + e.getMessage());
        }
    }
}

